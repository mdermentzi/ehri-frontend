<script lang="ts">

import FilterControl from './_filter-control';
import ButtonValidate from './_button-validate.vue';
import ButtonDelete from './_button-delete.vue';
import FilesTable from './_files-table';
import DragHandle from './_drag-handle';
import ModalInfo from './_modal-info';
import PanelLogWindow from './_panel-log-window';
import ModalIngestConfig from './_modal-ingest-config';
import PanelFilePreview from './_panel-file-preview';

import MixinTwoPanel from './_mixin-two-panel';
import MixinValidator from './_mixin-validator';
import MixinError from './_mixin-error';
import MixinPreview from './_mixin-preview';
import MixinStage from './_mixin-stage';
import MixinUtil from './_mixin-util';
import MixinTasklog from './_mixin-tasklog';
import {DatasetManagerApi} from '../api';

import {ImportConfig} from "../types";


export default {
  components: {FilterControl, FilesTable, PanelLogWindow, DragHandle, ModalInfo, PanelFilePreview, ButtonValidate, ButtonDelete, ModalIngestConfig},
  mixins: [MixinStage, MixinTwoPanel, MixinPreview, MixinValidator, MixinError, MixinUtil, MixinTasklog],
  props: {
    datasetId: String,
    fileStage: String,
    urlKey: {
      type: String,
      default: 'ingest-id',
    },
    config: Object,
    api: DatasetManagerApi,
  },
  data: function () {
    return {
      waiting: false,
      showOptions: false,
      propertyConfigs: [],
      opts: null,
    }
  },
  methods: {
    resumeMonitor: async function() {
      let jobId = this.getQueryParam(window.location.search, this.urlKey);
      if (jobId) {
        try {
          this.tab = "ingest";
          await this.monitor(this.config.monitorUrl(jobId), jobId);
        } finally {
          this.removeUrlState(this.urlKey);
        }
      }
    },
    doIngest: async function(opts: ImportConfig, commit: boolean) {
      this.waiting = true;

      // Save opts for the next time we open the config UI
      this.opts = opts;

      try {
        let {url, jobId} = await this.api.ingestFiles(this.datasetId, this.selectedKeys, opts, commit);
        // Switch to ingest tab...
        this.tab = "ingest";
        // Clear existing log...
        this.reset();
        this.showOptions = false;

        this.replaceUrlState(this.urlKey, jobId);
        await this.monitor(url, jobId);
      } catch (e) {
        this.showError("Error running ingest", e);
      } finally {
        this.removeUrlState(this.urlKey);
        this.waiting = false;
      }
    },
    loadConfig: async function() {
      this.opts = await this.api.getImportConfig(this.datasetId);
    },
    loadPropertyConfigs: async function() {
      this.loading = true;
      try {
        let data = await this.api.listFiles(this.datasetId, this.config.config);
        this.propertyConfigs = data.files.filter(f => f.key.endsWith(".properties"));
      } catch (e) {
        this.showError("Error loading files", e);
      } finally {
        this.loading = false;
      }
    },
  },
  created() {
    this.resumeMonitor();
    this.loadPropertyConfigs();
    this.loadConfig();
  },
};
</script>

<template>
  <div id="ingest-manager-container" class="stage-manager-container">
    <div class="actions-bar">
      <filter-control
          v-bind:filter="filter"
          v-on:filter="filterFiles"
          v-on:clear="clearFilter"
          v-on:refresh="load" />

      <button-validate
          v-bind:selected="selectedKeys.length"
          v-bind:disabled="files.length === 0 || jobId !== null"
          v-bind:active="validationRunning"
          v-on:validate="validateFiles(selectedTags)"
      />

      <button-delete
          v-bind:selected="selectedKeys.length"
          v-bind:disabled="files.length === 0 || jobId !== null"
          v-bind:active="deleting.length > 0"
          v-on:delete="deleteFiles(selectedKeys)"
      />

      <button v-bind:disabled="files.length === 0 || jobId" class="btn btn-sm btn-default"
              v-on:click.prevent="showOptions = !showOptions" v-if="selectedKeys.length">
        <i v-if="!jobId" class="fa fa-fw fa-database"/>
        <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
        Ingest Selected... ({{selectedKeys.length}})
      </button>
      <button v-bind:disabled="files.length===0 || jobId" class="btn btn-sm btn-default" v-on:click.prevent="showOptions = !showOptions"
              v-else>
        <i v-if="!jobId" class="fa fa-fw fa-database"/>
        <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
        Ingest All...
      </button>

      <modal-ingest-config
          v-if="showOptions"
          v-bind:waiting="waiting"
          v-bind:props="propertyConfigs"
          v-bind:opts="opts"
          v-bind:api="api"
          v-bind:config="config"
          v-bind:dataset-id="datasetId"
          v-on:saving="waiting = true"
          v-on:saved-config="doIngest"
          v-on:update="loadPropertyConfigs"
          v-on:close="showOptions = false" />

      <modal-info v-if="fileInfo !== null" v-bind:file-info="fileInfo" v-on:close="fileInfo = null"/>
    </div>

    <div id="ingest-panel-container" class="panel-container">
      <div class="top-panel">
        <files-table
            v-bind:fileStage="fileStage"
            v-bind:loaded="loaded"
            v-bind:loading-more="loadingMore"
            v-bind:files="files"
            v-bind:selected="selected"
            v-bind:previewing="previewing"
            v-bind:validating="validating"
            v-bind:validationResults="validationResults"
            v-bind:truncated="truncated"
            v-bind:deleting="deleting"
            v-bind:downloading="downloading"
            v-bind:loading-info="loadingInfo"
            v-bind:filter="filter.value"

            v-on:delete-files="deleteFiles"
            v-on:download-files="downloadFiles"
            v-on:validate-files="validateFiles"
            v-on:load-more="loadMore"
            v-on:show-preview="showPreview"
            v-on:item-selected="selectItem"
            v-on:item-deselected="deselectItem"
            v-on:deselect-all="deselect"
            v-on:toggle-all="toggleAll"
            v-on:toggle-file="toggleFile"
            v-on:info="info"
        />
      </div>

      <div id="ingest-status-panel" class="bottom-panel">
        <ul class="status-panel-tabs nav nav-tabs">
          <li class="nav-item">
            <a href="#" class="nav-link" v-bind:class="{'active': tab === 'preview'}"
               v-on:click.prevent="tab = 'preview'">
              File Preview
              <template v-if="previewing"> - {{ decodeURI(previewing.key) }}</template>
            </a>
          </li>
          <li class="nav-item">
            <a href="#" class="nav-link" v-bind:class="{'active': tab === 'validation'}"
               v-on:click.prevent="tab = 'validation'">
              Validation Log
            </a>
          </li>
          <li class="nav-item">
            <a href="#" class="nav-link" v-bind:class="{'active': tab === 'ingest'}"
               v-on:click.prevent="tab = 'ingest'">
              Ingest Log
            </a>
          </li>
          <li>
            <drag-handle
                v-bind:ns="fileStage"
                v-bind:p2="() => $root.$el.querySelector('#ingest-status-panel')"
                v-bind:container="() => $root.$el.querySelector('#ingest-panel-container')"
                v-on:resize="setPanelSize"
            />
          </li>
        </ul>

        <div class="status-panels">
          <div class="status-panel" v-show="tab === 'preview'">
            <panel-file-preview v-bind:dataset-id="datasetId"
                     v-bind:file-stage="fileStage"
                     v-bind:previewing="previewing"
                     v-bind:panel-size="panelSize"
                     v-bind:config="config"
                     v-bind:api="api"
                     v-bind:validation-results="validationResults"
                     v-on:validation-results="(tag, e) => this.$set(this.validationResults, tag, e)"
                     v-on:error="showError"
                     v-show="previewing !== null"/>
            <div class="panel-placeholder" v-if="previewing === null">
              No file selected.
            </div>
          </div>
          <div class="status-panel log-container" v-show="tab === 'validation'">
            <panel-log-window v-bind:log="validationLog" v-if="validationLog.length > 0"/>
            <div id="validation-placeholder" class="panel-placeholder" v-else>
              Validation log output will show here.
            </div>
          </div>
          <div class="status-panel log-container" v-show="tab === 'ingest'">
            <panel-log-window v-bind:log="log" v-if="log.length > 0"/>
            <div class="panel-placeholder" v-else>
              Ingest log output will show here.
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

