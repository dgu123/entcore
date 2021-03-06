/* Copyright © WebServices pour l'Éducation, 2014
 *
 * This file is part of ENT Core. ENT Core is a versatile ENT engine based on the JVM.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with ENT Core is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of ENT Core, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 */

package org.entcore.workspace.service.impl;

import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.mongodb.MongoQueryBuilder;
import fr.wseduc.mongodb.MongoUpdateBuilder;
import fr.wseduc.webutils.I18n;

import org.entcore.common.user.RepositoryEvents;
import org.entcore.workspace.Workspace;
import org.entcore.workspace.dao.DocumentDao;
import org.entcore.workspace.dao.RackDao;
import org.entcore.common.storage.Storage;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorkspaceRepositoryEvents implements RepositoryEvents {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceRepositoryEvents.class);
	private final MongoDb mongo = MongoDb.getInstance();
	private final Storage storage;
	private final Vertx vertx;
	private final boolean shareOldGroupsToUsers;

	public WorkspaceRepositoryEvents(Vertx vertx, Storage storage, boolean shareOldGroupsToUsers) {
		this.shareOldGroupsToUsers = shareOldGroupsToUsers;
		this.storage = storage;
		this.vertx = vertx;
	}

	@Override
	public void exportResources(final String exportId, final String userId, JsonArray g,
			final String exportPath, final String locale, String host, final Handler<Boolean> handler) {
		log.debug("Workspace export resources.");
		List<DBObject> groups = new ArrayList<>();
		groups.add(QueryBuilder.start("userId").is(userId).get());
		for (Object o : g) {
			if (!(o instanceof String)) continue;
			String gpId = (String) o;
			groups.add(QueryBuilder.start("groupId").is(gpId).get());
		}
		QueryBuilder b = new QueryBuilder().or(
				QueryBuilder.start("owner").is(userId).get(),
				QueryBuilder.start("shared").elemMatch(
						new QueryBuilder().or(groups.toArray(new DBObject[groups.size()])).get()
				).get(),
				QueryBuilder.start("old_shared").elemMatch(
						new QueryBuilder().or(groups.toArray(new DBObject[groups.size()])).get()
				).get()).put("file").exists(true);
		final JsonObject keys = new JsonObject().putNumber("file", 1).putNumber("name", 1);
		final JsonObject query = MongoQueryBuilder.build(b);
		mongo.find(DocumentDao.DOCUMENTS_COLLECTION, query, null,
				keys, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				final AtomicBoolean exported = new AtomicBoolean(false);
				final JsonArray documents = event.body().getArray("results");
				if ("ok".equals(event.body().getString("status")) && documents != null) {
					QueryBuilder b = QueryBuilder.start("to").is(userId).put("file").exists(true);
					final JsonObject q = MongoQueryBuilder.build(b);
					mongo.find(RackDao.RACKS_COLLECTION, q, null, keys,
							new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							JsonArray racks = event.body().getArray("results");
							if ("ok".equals(event.body().getString("status")) && racks != null) {
								final Set<String> usedFileName = new HashSet<>();
								final JsonObject alias = new JsonObject();
								final String [] ids = new String[racks.size() + documents.size()];
								for (int i = 0; i < documents.size(); i++) {
									JsonObject j = documents.get(i);
									ids[i] = j.getString("file");
									String fileName = j.getString("name");
									if (fileName != null && fileName.contains("/")) {
										fileName = fileName.replaceAll("/", "-");
									}
									if (usedFileName.add(fileName)) {
										alias.putString(ids[i], fileName);
									} else {
										alias.putString(ids[i], ids[i] + "_" + fileName);
									}
								}
								for (int i = 0; i < racks.size(); i++) {
									JsonObject j = racks.get(i);
									ids[i] = j.getString("file");
									String fileName = j.getString("name");
									if (fileName != null && fileName.contains("/")) {
										fileName = fileName.replaceAll("/", "-");
									}
									if (usedFileName.add(fileName)) {
										alias.putString(ids[i], fileName);
									} else {
										alias.putString(ids[i], ids[i] + "_" + fileName);
									}
								}
								exportFiles(alias, ids, exportPath, locale, exported, handler);
							} else {
								log.error("Rack " + q.encode() + " - " + event.body().getString("message"));
								handler.handle(exported.get());
							}
						}
					});
				} else {
					log.error("Documents " + query.encode() + " - " +event.body().getString("message"));
					handler.handle(exported.get());
				}
			}
		});
	}

	private void exportFiles(final JsonObject alias, final String[] ids,
			String exportPath, String locale, final AtomicBoolean exported, final Handler<Boolean> handler) {
		createExportDirectory(exportPath, locale, new Handler<String>() {
			@Override
			public void handle(String path) {
				if (path != null) {
					storage.writeToFileSystem(ids, path, alias, new Handler<JsonObject>() {
						@Override
						public void handle(JsonObject event) {
							if ("ok".equals(event.getString("status"))) {
								exported.set(true);
								handler.handle(exported.get());
							} else {
								log.error("Write to fs : " + new JsonArray(ids).encode() + " - " + event.encode());
								handler.handle(exported.get());
							}
						}
					});
				} else {
					log.error("Create export directory error.");
					handler.handle(exported.get());
				}
			}
		});
	}

	private void createExportDirectory(String exportPath, String locale, final Handler<String> handler) {
		final String path = exportPath + File.separator +
				I18n.getInstance().translate("workspace.title", I18n.DEFAULT_DOMAIN, locale);
		vertx.fileSystem().mkdir(path, new Handler<AsyncResult<Void>>() {
			@Override
			public void handle(AsyncResult<Void> event) {
				if (event.succeeded()) {
					handler.handle(path);
				} else {
					handler.handle(null);
				}
			}
		});
	}

	@Override
	public void deleteGroups(JsonArray groups) {
		for (Object o : groups) {
			if (!(o instanceof JsonObject)) continue;
			final JsonObject j = (JsonObject) o;
			final JsonObject query = MongoQueryBuilder.build(
				QueryBuilder.start("shared.groupId").is(j.getString("group")));
			final Handler<Message<JsonObject>> handler = new Handler<Message<JsonObject>>() {
				@Override
				public void handle(Message<JsonObject> event) {
					if (!"ok".equals(event.body().getString("status"))) {
						log.error("Error updating documents with group " +
								j.getString("group") + " : " + event.body().encode());
					} else {
						log.info("Documents with group " + j.getString("group") +
								" updated : " + event.body().getInteger("number"));
					}
				}
			};
			if (shareOldGroupsToUsers) {
				JsonArray userShare = new JsonArray();
				for (Object u : j.getArray("users")) {
					JsonObject share = new JsonObject()
							.putString("userId", u.toString())
							.putBoolean("org-entcore-workspace-service-WorkspaceService|copyDocuments", true)
							.putBoolean("org-entcore-workspace-service-WorkspaceService|getDocument", true);
					userShare.addObject(share);
				}
				JsonObject update = new JsonObject()
						.putObject("$addToSet",
								new JsonObject().putObject("shared",
										new JsonObject().putArray("$each", userShare)));
				mongo.update(DocumentDao.DOCUMENTS_COLLECTION, query, update, false, true, handler);
			} else {
				final MongoUpdateBuilder update = new MongoUpdateBuilder()
						.pull("shared", new JsonObject().putString("groupId", j.getString("group")))
						.addToSet("old_shared", new JsonObject().putString("groupId", j.getString("group")));
				mongo.update(DocumentDao.DOCUMENTS_COLLECTION, query, update.build(), false, true, handler);
			}
		}
	}

	@Override
	public void deleteUsers(JsonArray users) {
		String [] userIds = new String[users.size()];
		JsonArray oldShared = new JsonArray();
		for (int i = 0; i < users.size(); i++) {
			JsonObject j = users.get(i);
			String id = j.getString("id");
			userIds[i] = id;
			oldShared.add(new JsonObject().putString("userId", id));
		}
		final JsonObject queryDocuments = MongoQueryBuilder.build(QueryBuilder.start("owner").in(userIds));
		deleteFiles(queryDocuments, DocumentDao.DOCUMENTS_COLLECTION);
		final JsonObject queryRacks = MongoQueryBuilder.build(QueryBuilder.start("to").in(userIds));
		deleteFiles(queryRacks, RackDao.RACKS_COLLECTION);
		deleteFiles(queryDocuments, Workspace.REVISIONS_COLLECTION);

		final JsonObject query = MongoQueryBuilder.build(QueryBuilder.start("shared.userId").in(userIds));
		JsonObject update = new JsonObject()
				.putObject("$pull", new JsonObject()
						.putObject("shared", MongoQueryBuilder.build(QueryBuilder.start("userId").in(userIds))))
				.putObject("$addToSet", new JsonObject()
						.putObject("old_shared", new JsonObject().putArray("$each", oldShared)));
		mongo.update(DocumentDao.DOCUMENTS_COLLECTION, query, update, false, true, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				if (!"ok".equals(event.body().getString("status"))) {
					log.error(event.body().getString("message"));
				}
			}
		});
	}

	private void deleteFiles(final JsonObject query, final String collection) {
		mongo.find(collection, query, null,
				new JsonObject().putNumber("file", 1), new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> res) {
				String status = res.body().getString("status");
				JsonArray results = res.body().getArray("results");
				if ("ok".equals(status) && results != null && results.size() > 0) {
					for (Object o : results) {
						if (!(o instanceof JsonObject)) continue;
						final String file = ((JsonObject) o).getString("file");
						storage.removeFile(file,
								new Handler<JsonObject>() {

									@Override
									public void handle(JsonObject event) {
										if (event == null) {
											log.error("Error deleting file " + file);
										} else if (!"ok".equals(event.getString("status"))) {
											log.error("Error deleting file " + file + " : " + event.encode());
										}
									}
								});
					}
					mongo.delete(collection, query, new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							if (!"ok".equals(event.body().getString("status"))) {
								log.error("Error deleting documents : " + event.body().encode());
							} else {
								log.info("Documents deleted : " + event.body().getInteger("number"));
							}
						}
					});
				}
			}
		});
	}

}
