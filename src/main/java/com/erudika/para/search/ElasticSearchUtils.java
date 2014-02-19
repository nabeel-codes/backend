/*
 * Copyright 2014 Alex Bogdanovski <alex@erudika.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You can reach the author at: https://github.com/albogdano
 */
package com.erudika.para.search;

import com.erudika.para.Para;
import com.erudika.para.core.ParaObject;
import com.erudika.para.persistence.DAO;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Utils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.optimize.OptimizeResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper utilities for connecting to an ElasticSearch cluster.
 * @author Alex Bogdanovski <alex@erudika.com>
 */
public final class ElasticSearchUtils {

	private static final Logger logger = LoggerFactory.getLogger(ElasticSearchUtils.class);
	private static Client searchClient;
	private static Node searchNode;

	private ElasticSearchUtils() { }

	/**
	 * Creates an instance of the client that talks to ElaasticSearch.
	 * @return a client instance
	 */
	public static Client getClient() {
		if (searchClient != null) {
			return searchClient;
		}
		ImmutableSettings.Builder settings = ImmutableSettings.settingsBuilder();
		settings.put("client.transport.sniff", true);
		settings.put("client.transport.sniff", true);
		settings.put("action.disable_delete_all_indices", true);

		if (Config.IN_PRODUCTION) {
			settings.put("cloud.aws.access_key", Config.AWS_ACCESSKEY);
			settings.put("cloud.aws.secret_key", Config.AWS_SECRETKEY);
			settings.put("path.data", "/var/lib/elasticsearch/data");
			settings.put("path.work", "/var/lib/elasticsearch/work");
	//		settings.put("path.logs", "/var/log/elasticsearch/");

			settings.put("cloud.aws.region", Config.AWS_REGION);
			settings.put("network.tcp.keep_alive", true);
//			settings.put("index.number_of_shards", 5);
//			settings.put("index.number_of_replicas", 0);
//
			settings.put("discovery.type", "ec2");
			settings.put("discovery.ec2.groups", "elasticsearch");
//			settings.put("discovery.ec2.availability_zones", "eu-west-1a");
			searchNode = NodeBuilder.nodeBuilder().settings(settings).
					clusterName(Config.CLUSTER_NAME).client(true).data(false).node();
			searchClient = searchNode.client();
		} else if ("embedded".equals(Config.ENVIRONMENT)) {
			// for testing only
			settings.put("path.data", "target/elasticsearch/data");
			settings.put("path.work", "target/elasticsearch/work");
			searchNode = NodeBuilder.nodeBuilder().settings(settings).
					clusterName(Config.CLUSTER_NAME).local(true).data(true).node();
			searchClient = searchNode.client();
			if (!existsIndex(Config.APP_NAME_NS)) {
				createIndex(Config.APP_NAME_NS);
			}
		} else {
			searchClient = new TransportClient(settings.put("cluster.name", Config.CLUSTER_NAME).build());
				((TransportClient) searchClient).addTransportAddress(
						new InetSocketTransportAddress("localhost", 9300));
		}

		Para.addDestroyListener(new Para.DestroyListener() {
			public void onDestroy() {
				shutdownClient();
			}
		});

		return searchClient;
	}

	/**
	 * Stops the client instance and releases resources.
	 */
	public static void shutdownClient() {
		if (searchClient != null) {
			searchClient.close();
			searchClient = null;
		}
		if (searchNode != null) {
			searchNode.close();
			searchNode = null;
		}
	}

	/**
	 * Creates a new search index.
	 * @param appName the index name (alias)
	 * @return true if created
	 */
	public static boolean createIndex(String appName) {
		if (StringUtils.isBlank(appName) || StringUtils.containsWhitespace(appName) || existsIndex(appName)) {
			return false;
		}
		try {
			NodeBuilder nb = NodeBuilder.nodeBuilder();
			nb.settings().put("number_of_shards", "5");
			nb.settings().put("number_of_replicas", "0");
			nb.settings().put("auto_expand_replicas", "0-all");
			nb.settings().put("analysis.analyzer.default.type", "standard");
			nb.settings().putArray("analysis.analyzer.default.stopwords",
					"arabic", "armenian", "basque", "brazilian", "bulgarian", "catalan",
					"czech", "danish", "dutch", "english", "finnish", "french", "galician",
					"german", "greek", "hindi", "hungarian", "indonesian", "italian",
					"norwegian", "persian", "portuguese", "romanian", "russian", "spanish",
					"swedish", "turkish");

			String name = appName + "1";
			CreateIndexRequestBuilder create = getClient().admin().indices().prepareCreate(name).
					setSettings(nb.settings().build());

			// special system mappings (all the rest are dynamic)
			create.addMapping("_default_", getDefaultMapping());
			create.execute().actionGet();

			getClient().admin().indices().prepareAliases().addAlias(name, appName).execute().actionGet();
		} catch (Exception e) {
			logger.warn(null, e);
			return false;
		}
		return true;
	}

	/**
	 * Deletes an existing search index.
	 * @param appName the index name (alias)
	 * @return true if deleted
	 */
	public static boolean deleteIndex(String appName) {
		if (StringUtils.isBlank(appName) || !existsIndex(appName)) {
			return false;
		}
		try {
			getClient().admin().indices().prepareDelete(appName).execute().actionGet();
		} catch (Exception e) {
			logger.warn(null, e);
			return false;
		}
		return true;
	}

	/**
	 * Checks if the index exists.
	 * @param appName the index name (alias)
	 * @return true if exists
	 */
	public static boolean existsIndex(String appName) {
		if (StringUtils.isBlank(appName)) {
			return false;
		}
		boolean exists = false;
		try {
			exists = getClient().admin().indices().prepareExists(appName).execute().
					actionGet().isExists();
		} catch (Exception e) {
			logger.warn(null, e);
		}
		return exists;
	}

	/**
	 * Rebuilds an index. Reads objects from the data store and indexes them in batches.
	 * @param appName the index name (alias)
	 * @param dao an instance of the persistence class
	 * @return true if successful
	 */
	public static boolean rebuildIndex(String appName, DAO dao) {
		if (StringUtils.isBlank(appName) || dao == null) {
			return false;
		}
		try {
			if (!existsIndex(appName)) {
				return false;
			}
			String oldName = getIndexNameForAlias(appName);
			if (oldName == null) {
				return false;
			}
			String newName = oldName + "_" + Utils.timestamp();

			logger.info("rebuildIndex(): {}", appName);

			BulkRequestBuilder brb = getClient().prepareBulk();
			BulkResponse resp = null;
			String lastKey = null;

			List<ParaObject> list = dao.readPage(appName, null);

			if (!list.isEmpty()) {
				do {
					for (ParaObject obj : list) {
						brb.add(getClient().prepareIndex(appName, obj.getClassname(), obj.getId()).
								setSource(Utils.getAnnotatedFields(obj)));
						lastKey = obj.getId();
					}
					// bulk index 1000 objects
					if (brb.numberOfActions() > 100) {
						resp = brb.execute().actionGet();
						logger.info("rebuildIndex(): indexed {}, hasFailures: {}",
								brb.numberOfActions(), resp.hasFailures());
					}
				} while(!(list = dao.readPage(appName, lastKey)).isEmpty());
			}

			// anything left after loop? index that too
			if (brb.numberOfActions() > 0) {
				resp = brb.execute().actionGet();
				logger.info("rebuildIndex(): indexed {}, hasFailures: {}",
						brb.numberOfActions(), resp.hasFailures());
			}

			// switch to alias NEW_INDEX -> ALIAS, OLD_INDEX -> X
			getClient().admin().indices().prepareAliases().
					addAlias(newName, appName).
					removeAlias(oldName, appName).execute().actionGet();

			// delete the old index
			deleteIndex(oldName);
		} catch (Exception e) {
			logger.warn(null, e);
			return false;
		}
		return true;
	}

	/**
	 * Optimizes an index. This method might be deprecated in the future.
	 * @param appName the index name (alias)
	 * @return true if successful
	 */
	public static boolean optimizeIndex(String appName) {
		if (StringUtils.isBlank(appName)) {
			return false;
		}
		boolean result = false;
		try {
			OptimizeResponse resp = getClient().admin().indices().
					prepareOptimize(appName).execute().actionGet();

			result = resp.getFailedShards() == 0;
		} catch (Exception e) {
			logger.warn(null, e);
		}
		return result;
	}

	/**
	 * Returns information about a cluster.
	 * @return a map of key value pairs containing cluster information
	 */
	public static Map<String, String> getSearchClusterMetadata() {
		Map<String, String> md = new HashMap<String, String>();
		NodesInfoResponse res = getClient().admin().cluster().nodesInfo(new NodesInfoRequest().all()).actionGet();
		md.put("cluser.name", res.getClusterName().toString());

		for (NodeInfo nodeInfo : res) {
			md.put("node.name", nodeInfo.getNode().getName());
			md.put("node.name", nodeInfo.getNode().getAddress().toString());
			md.put("node.data", Boolean.toString(nodeInfo.getNode().isDataNode()));
			md.put("node.getClient()", Boolean.toString(nodeInfo.getNode().isClientNode()));
			md.put("node.version", nodeInfo.getNode().getVersion().toString());
		}
		return md;
	}

	/**
	 * Returns the real index name for a given alias.
	 * @param appName the index name (alias)
	 * @return the real index name (not alias)
	 */
	public static String getIndexNameForAlias(String appName) {
		if (StringUtils.isBlank(appName)) {
			return null;
		}
		GetAliasesResponse get = getClient().admin().indices().
				prepareGetAliases(appName).execute().actionGet();
		ImmutableOpenMap<String, List<AliasMetaData>> aliases = get.getAliases();
		if (aliases.size() > 1) {
			logger.warn("More than one index for alias {}", appName);
		} else {
			return aliases.keysIt().next();
		}
		return null;
	}

	/**
	 * A list of default mappings that are defined upon index creation.
	 * @return a json object of default mappings
	 * @throws Exception 
	 */
	private static XContentBuilder getDefaultMapping() throws Exception {
		return XContentFactory.jsonBuilder().
			startObject().
				startObject("_default_").
					startObject("properties").
						startObject("latlng").field("type", "geo_point").field("lat_lon", true).endObject().
						startObject("tag").field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._ID).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._KEY).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._SALT).field("type", "string").field("index", "not_analyzed").endObject().
//						startObject(Config._TAGS).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._EMAIL).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._GROUPS).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._UPDATED).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._PASSWORD).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._PARENTID).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._CREATORID).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._CLASSNAME).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._AUTHTOKEN).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._TIMESTAMP).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._IDENTIFIER).field("type", "string").field("index", "not_analyzed").endObject().
						startObject(Config._RESET_TOKEN).field("type", "string").field("index", "not_analyzed").endObject().
					endObject().
				endObject().
			endObject();
	}

}