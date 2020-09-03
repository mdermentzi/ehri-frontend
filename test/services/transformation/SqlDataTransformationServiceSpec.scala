package services.transformation

import akka.actor.ActorSystem
import helpers._
import models.{DataTransformation, DataTransformationInfo}
import play.api.db.Database
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.PlaySpecification

class SqlDataTransformationServiceSpec extends PlaySpecification {

  private val actorSystem = new GuiceApplicationBuilder().build().injector.instanceOf[ActorSystem]

  def service(implicit db: Database) = SqlDataTransformationService(db, actorSystem)

  "Data transformation service" should {
    "list items" in withDatabaseFixture("data-transformation-fixtures.sql") { implicit db =>
      val dts = await(service.list())
      dts.size must_== 1
    }

    "locate items" in withDatabaseFixture("data-transformation-fixtures.sql") { implicit db =>
      val dt = await(service.get(1))
      dt.name must_== "test"
    }

    "delete items" in withDatabaseFixture("data-transformation-fixtures.sql") { implicit db =>
      await(service.delete(1)) must_== true
    }

    "create items" in withDatabaseFixture("data-transformation-fixtures.sql") { implicit db =>
      val dt = await(service.create(DataTransformationInfo("test2", DataTransformation.TransformationType.Xslt, "foo", "comment"), Some("r2")))
      dt.name must_== "test2"
    }

    "update items" in withDatabaseFixture("data-transformation-fixtures.sql") { implicit db =>
      val dt = await(service.get(1))
      val dt2 = await(service.update(dt.id, DataTransformationInfo("blah", dt.bodyType, dt.body, dt.comments), Some("r2")))
      dt2.name must_== "blah"
    }

    "save repository configs" in withDatabaseFixture("data-transformation-fixtures.sql") { implicit db =>
      val saved = await(service.saveConfig("r2", Seq(1)))
      saved must_== 1

      val saved2 = await(service.saveConfig("r2", Seq.empty))
      saved2 must_== 0

      val saved3 = await(service.saveConfig("r2", Seq(1)))
      saved3 must_== 1

      val dt = await(service.create(DataTransformationInfo("test2", DataTransformation.TransformationType.Xslt, "foo", "comment"), Some("r2")))
      val saved4 = await(service.saveConfig(repoId = "r2", Seq(1, dt.id)))
      saved4 must_== 2

      val saved5 = await(service.saveConfig("r2", Seq.empty))
      saved5 must_== 0
    }

    "get repository configs" in withDatabaseFixture("data-transformation-fixtures.sql") { implicit db =>
      val dt = await(service.create(DataTransformationInfo("test2", DataTransformation.TransformationType.Xslt, "foo", "comment"), Some("r2")))
      await(service.saveConfig("r2", Seq(dt.id, 1)))
      val configs = await(service.getConfig("r2"))
      configs.size must_== 2
      configs.head.name must_== "test2"
    }
  }
}