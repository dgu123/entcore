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

package org.entcore.conversation.service.impl;

import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.neo4j.StatementsBuilder;
import org.entcore.common.user.RepositoryEvents;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

public class ConversationRepositoryEvents implements RepositoryEvents {

	private static final Logger log = LoggerFactory.getLogger(ConversationRepositoryEvents.class);
	private final Neo4j neo4j = Neo4j.getInstance();

	@Override
	public void exportResources(String exportId, String userId, JsonArray groups, String exportPath,
			String locale) {

	}

	@Override
	public void deleteGroups(JsonArray groups) {
		String q1 = "MATCH (m:ConversationMessage {from : {group}}) SET m.fromName = {groupName} ";
		String q2 =
				"MATCH (m:ConversationMessage) WHERE {group} IN m.to " +
				"SET m.toName = coalesce(m.toName, []) + {groupName} ";
		String q3 =
				"MATCH (m:ConversationMessage) WHERE {group} IN m.cc " +
				"SET m.ccName = coalesce(m.ccName, []) + {groupName} ";
		StatementsBuilder b = new StatementsBuilder();
		for (Object o : groups) {
			if (!(o instanceof JsonObject)) continue;
			JsonObject params = (JsonObject) o;
			params.removeField("users");
			b.add(q1, params);
			b.add(q2, params);
			b.add(q3, params);
		}
		neo4j.executeTransaction(b.build(), null, true, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				if (!"ok".equals(event.body().getString("status"))) {
					log.error("Error updating delete groups in conversation : " + event.body().encode());
				}
			}
		});
	}

	@Override
	public void deleteUsers(JsonArray users) {
		JsonArray userIds = new JsonArray();
		for (Object o : users) {
			if (!(o instanceof JsonObject)) continue;
			userIds.addString(((JsonObject) o).getString("id"));
		}
		String query =
				"MATCH (c:Conversation)-[r:HAS_CONVERSATION_FOLDER]->(f:ConversationFolder) " +
				"WHERE c.userId IN {userIds} " +
				"OPTIONAL MATCH f-[r2]-()" +
				"DELETE c, r, f, r2 ";
		StatementsBuilder b = new StatementsBuilder()
			.add(query, new JsonObject().putArray("userIds", userIds));

		query = "MATCH (m:ConversationMessage) " +
				"WHERE NOT(m<-[:HAS_CONVERSATION_MESSAGE|HAD_CONVERSATION_MESSAGE]-()) " +
				"OPTIONAL MATCH m-[pr:PARENT_CONVERSATION_MESSAGE]-() " +
				"DELETE m, pr ";
		b.add(query);

		String q1 = "MATCH (m:ConversationMessage {from : {id}}) SET m.fromName = {displayName} ";
		String q2 =
				"MATCH (m:ConversationMessage) WHERE {id} IN m.to " +
				"SET m.toName = coalesce(m.toName, []) + {displayName} ";
		String q3 =
				"MATCH (m:ConversationMessage) WHERE {id} IN m.cc " +
				"SET m.ccName = coalesce(m.ccName, []) + {displayName} ";
		for (Object o : users) {
			if (!(o instanceof JsonObject)) continue;
			JsonObject params = (JsonObject) o;
			b.add(q1, params);
			b.add(q2, params);
			b.add(q3, params);
		}
		neo4j.executeTransaction(b.build(), null, true, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				if (!"ok".equals(event.body().getString("status"))) {
					log.error("Error deleting conversation data : " + event.body().encode());
				}
			}
		});
	}

}