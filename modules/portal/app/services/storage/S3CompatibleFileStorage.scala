package services.storage

import java.net.URI

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.Materializer
import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.headers.CannedAcl
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.ByteString
import com.amazonaws.auth.{AWSCredentials, AWSCredentialsProvider}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.regions.AwsRegionProvider
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.{AmazonS3Exception, BucketVersioningConfiguration, DeleteObjectsRequest, GeneratePresignedUrlRequest, GetObjectMetadataRequest, ListObjectsRequest, ListVersionsRequest, SetBucketVersioningConfigurationRequest}
import play.api.Logger

import scala.collection.JavaConverters._
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}


object S3CompatibleFileStorage {
  def apply(config: com.typesafe.config.Config)(implicit mat: Materializer): S3CompatibleFileStorage = {
    val credentials = new AWSCredentialsProvider {
      override def getCredentials: AWSCredentials = new AWSCredentials {
        override def getAWSAccessKeyId: String = config.getString("credentials.access-key-id")

        override def getAWSSecretKey: String = config.getString("credentials.secret-access-key")
      }

      override def refresh(): Unit = {}
    }

    val region = new AwsRegionProvider {
      override def getRegion: String = config.getString("region.default-region")
    }

    val endpoint: Option[String] = if (config.hasPath("endpoint"))
      Some(config.getString("endpoint")) else None

    new S3CompatibleFileStorage(credentials, region, endpoint)(mat)
  }
}

case class S3CompatibleFileStorage(
  credentials: AWSCredentialsProvider,
  region: AwsRegionProvider,
  endpointUrl: Option[String] = None
)(implicit mat: Materializer) extends FileStorage {
  private val logger = Logger(getClass)
  private implicit val ec: ExecutionContext = mat.executionContext
  private implicit val actorSystem: ActorSystem = mat.system

  private val endpoint = endpointUrl.fold(
    ifEmpty = S3Ext(actorSystem)
      .settings
      .withCredentialsProvider(credentials)
      .withS3RegionProvider(region)
  )(
    url => S3Ext(actorSystem)
      .settings
      .withCredentialsProvider(credentials)
      .withS3RegionProvider(region)
      .withEndpointUrl(url)
  )

  private val client = endpointUrl.fold(
    ifEmpty = AmazonS3ClientBuilder.standard()
      .withCredentials(credentials)
      .withRegion(region.getRegion)
      .build()
  )(
    url => AmazonS3ClientBuilder.standard()
      .withCredentials(credentials)
      .withEndpointConfiguration(new EndpointConfiguration(
        url,
        region.getRegion
      ))
      .build()
  )

  override def uri(classifier: String, path: String, duration: FiniteDuration = 10.minutes, contentType: Option[String] = None, versionId: Option[String] = None): URI = {
    val expTime = new java.util.Date()
    val expTimeMillis = expTime.getTime + duration.toMillis
    expTime.setTime(expTimeMillis)

    val method = if (contentType.isDefined) com.amazonaws.HttpMethod.PUT else com.amazonaws.HttpMethod.GET
    val psur = new GeneratePresignedUrlRequest(classifier, path)
      .withExpiration(expTime)
      .withMethod(method)
    contentType.foreach(psur.setContentType)
    versionId.foreach(psur.setVersionId)

    client.generatePresignedUrl(psur).toURI
  }

  override def count(classifier: String, prefix: Option[String]): Future[Int] =
    countFilesWithPrefix(classifier, prefix)

  override def info(bucket: String, path: String, versionId: Option[String] = None): Future[Option[(FileMeta, Map[String, String])]] = Future {
    val omr = new GetObjectMetadataRequest(bucket, path)
    versionId.foreach(omr.setVersionId)

    try {
      val meta = client.getObjectMetadata(omr)
      val fm = FileMeta(
        bucket,
        path,
        meta.getLastModified.toInstant,
        meta.getContentLength,
        Option(meta.getETag),
        Option(meta.getContentType),
        Option(meta.getVersionId)
      )
      Some((fm, meta.getUserMetadata.asScala.toMap))
    } catch {
      case e: AmazonS3Exception => None
    }
  }(ec)

  override def get(bucket: String, path: String, versionId: Option[String] = None): Future[Option[(FileMeta, Source[ByteString, _])]] = S3
    .download(bucket, path, versionId = versionId)
    .withAttributes(S3Attributes.settings(endpoint))
    .runWith(Sink.headOption).map(_.flatten)
    .map {
      case Some((src, meta)) => Some(infoToMeta(bucket, path, meta) -> src)
      case _ => None
    }

  override def putBytes(bucket: String, path: String, src: Source[ByteString, _], contentType: Option[String] = None,
    public: Boolean = false, meta: Map[String, String] = Map.empty): Future[URI] = {
    val cType = contentType.map(ContentType.parse) match {
      case Some(Right(ct)) => ct
      case _ =>
        val mediaType: MediaType = MediaTypes.forExtension(path.substring(path.lastIndexOf(".") + 1))
        ContentType(mediaType, () => HttpCharsets.`UTF-8`)
    }
    logger.debug(s"Uploading file: $path to $bucket with content-type: $contentType")
    val acl = if (public) CannedAcl.PublicRead else CannedAcl.AuthenticatedRead

    val uploader = S3.multipartUpload(bucket, path, contentType = cType, cannedAcl = acl, metaHeaders = MetaHeaders(meta))
    val sink = endpointUrl.fold(uploader)(_ => uploader.withAttributes(S3Attributes.settings(endpoint)))

    src.runWith(sink).map(r => new URI(r.location.toString))
  }

  override def putFile(classifier: String, path: String, file: java.io.File, contentType: Option[String] = None,
    public: Boolean = false, meta: Map[String, String] = Map.empty): Future[URI] =
    putBytes(classifier, path, FileIO.fromPath(file.toPath), contentType, public, meta)

  override def deleteFiles(classifier: String, paths: String*): Future[Seq[String]] = Future {
    deleteKeys(classifier, paths)
  }(ec)

  override def deleteFilesWithPrefix(classifier: String, prefix: String): Future[Seq[String]] = Future {
    @scala.annotation.tailrec
    def deleteBatch(done: Seq[String] = Seq.empty): Seq[String] = {
      val fm = listPrefix(classifier, Some(prefix), done.lastOption, max = 1000)
      val keys = fm.files.map(_.key)
      deleteKeys(classifier, keys)
      if (fm.truncated) deleteBatch(done ++ keys)
      else done ++ keys
    }

    deleteBatch()
  }(ec)

  override def streamFiles(classifier: String, prefix: Option[String]): Source[FileMeta, NotUsed] =
    S3.listBucket(classifier, prefix)
      // FIXME: Switch to ListObjectsV2 when Digital Ocean supports it
      .withAttributes(S3Attributes.settings(endpoint
        .withListBucketApiVersion(ApiVersion.ListBucketVersion1)))
      .map(f => FileMeta(classifier, f.key, f.lastModified, f.size, Some(f.eTag)))

  override def listFiles(classifier: String, prefix: Option[String], after: Option[String], max: Int): Future[FileList] = Future {
    listPrefix(classifier, prefix, after, max)
  }(ec)

  override def listVersions(classifier: String, path: String, after: Option[String] = None): Future[FileList] =
    listVersions(classifier, Some(path), None, after, max = 200)

  override def setVersioned(classifier: String, enabled: Boolean): Future[Unit] = Future {
    val status = if (enabled) BucketVersioningConfiguration.ENABLED
    else BucketVersioningConfiguration.OFF
    val bvc = new BucketVersioningConfiguration().withStatus(status)
    val bcr = new SetBucketVersioningConfigurationRequest(classifier, bvc)
    client.setBucketVersioningConfiguration(bcr)
  }(ec)

  override def isVersioned(classifier: String): Future[Boolean] = Future {
    val bvc = client.getBucketVersioningConfiguration(classifier)
    bvc.getStatus == BucketVersioningConfiguration.ENABLED
  }(ec)


  private def infoToMeta(bucket: String, path: String, meta: ObjectMetadata): FileMeta = FileMeta(
    bucket,
    path,
    java.time.Instant.ofEpochMilli(meta.lastModified.clicks),
    meta.getContentLength,
    meta.eTag,
    meta.contentType,
    meta.versionId
  )

  private def countFilesWithPrefix(classifier: String, prefix: Option[String] = None): Future[Int] = Future {
    @scala.annotation.tailrec
    def countBatch(done: Int = 0, last: Option[String] = None): Int = {
      val fm = listPrefix(classifier, prefix, last, max = 1000)
      val count = fm.files.size
      if (fm.truncated) countBatch(done + count, fm.files.lastOption.map(_.key))
      else done + count
    }

    countBatch()
  }(ec)

  private def listVersions(classifier: String, prefix: Option[String], after: Option[String], afterVersion: Option[String], max: Int): Future[FileList] = Future {
    listPrefixVersions(classifier, prefix, after, afterVersion, max)
  }(ec)

  private def listPrefixVersions(classifier: String, prefix: Option[String], after: Option[String], afterVersion: Option[String], max: Int): FileList = {
    val lvr = new ListVersionsRequest()
      .withBucketName(classifier)
      .withMaxResults(max)
    prefix.foreach(lvr.setPrefix)
    after.foreach(lvr.setKeyMarker)
    afterVersion.foreach(lvr.setVersionIdMarker)
    val r = client.listVersions(lvr)
    FileList(r.getVersionSummaries.asScala.map { f =>
      FileMeta(
        f.getBucketName,
        f.getKey,
        f.getLastModified.toInstant,
        f.getSize,
        eTag = Some(f.getETag),
        versionId = Some(f.getVersionId)
      )
    }, r.isTruncated)
  }

  private def deleteKeys(classifier: String, paths: Seq[String]) = {
    val dor = new DeleteObjectsRequest(classifier).withKeys(paths: _*)
    client.deleteObjects(dor).getDeletedObjects.asScala.map(_.getKey)
  }

  private def listPrefix(classifier: String, prefix: Option[String], after: Option[String], max: Int) = {
    // FIXME: Update this to ListObjectsV2 when Digital Ocean
    // implement support for the StartAfter parameter.
    // For now the marker param in ListObject (v1) seems
    // to do the same thing.
    val req = new ListObjectsRequest()
      .withBucketName(classifier)
      .withMaxKeys(max)
    after.foreach(req.setMarker)
    prefix.foreach(req.setPrefix)
    val r = client.listObjects(req)
    FileList(r.getObjectSummaries.asScala.map { f =>
      FileMeta(f.getBucketName, f.getKey, f.getLastModified.toInstant, f.getSize, Some(f.getETag))
    }.toList, r.isTruncated)
  }
}
