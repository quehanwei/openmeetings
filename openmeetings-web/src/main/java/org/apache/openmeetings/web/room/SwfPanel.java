/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.web.room;

import static org.apache.openmeetings.util.OpenmeetingsVariables.webAppRootKey;
import static org.apache.openmeetings.web.app.Application.getBean;
import static org.apache.openmeetings.web.app.WebSession.getLanguage;
import static org.apache.openmeetings.web.app.WebSession.getSid;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.openmeetings.core.session.SessionManager;
import org.apache.openmeetings.db.dao.room.InvitationDao;
import org.apache.openmeetings.db.dao.room.RoomDao;
import org.apache.openmeetings.db.dao.server.SOAPLoginDao;
import org.apache.openmeetings.db.dao.server.ServerDao;
import org.apache.openmeetings.db.dao.server.SessiondataDao;
import org.apache.openmeetings.db.entity.server.SOAPLogin;
import org.apache.openmeetings.db.entity.server.Server;
import org.apache.openmeetings.web.app.Client;
import org.apache.openmeetings.web.app.WebSession;
import org.apache.openmeetings.web.common.BasePanel;
import org.apache.wicket.RuntimeConfigurationType;
import org.apache.wicket.ajax.AbstractAjaxTimerBehavior;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.PriorityHeaderItem;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.request.mapper.parameter.PageParameters;
import org.apache.wicket.request.mapper.parameter.PageParametersEncoder;
import org.apache.wicket.request.resource.JavaScriptResourceReference;
import org.apache.wicket.request.resource.ResourceReference;
import org.apache.wicket.util.string.StringValue;
import org.apache.wicket.util.time.Duration;
import org.red5.logging.Red5LoggerFactory;
import org.slf4j.Logger;

public class SwfPanel extends BasePanel {
	private static final long serialVersionUID = 1L;
	private static final String WICKET_ROOM_ID = "wicketroomid";
	public static final String PARAM_PUBLIC_SID = "publicSid";
	public static final String PARAM_URL = "url";
	private static final Logger log = Red5LoggerFactory.getLogger(SwfPanel.class, webAppRootKey);
	private Long roomId = null;
	
	public SwfPanel(String id) {
		this(id, new PageParameters());
	}
	
	public SwfPanel(String id, Client c) {
		this(id, addServer(c.getRoomId(), true).add("uid", c.getUid()));
	}
	
	public SwfPanel(String id, PageParameters pp) {
		super(id);
		//OK let's find the room
		try {
			StringValue room = pp.get(WICKET_ROOM_ID);
			StringValue secureHash = pp.get(WebSession.SECURE_HASH);
			StringValue invitationHash = pp.get(WebSession.INVITATION_HASH);
			if (!room.isEmpty()) {
				roomId = room.toLongObject();
			} else if (!secureHash.isEmpty()) {
				if (WebSession.get().signIn(secureHash.toString(), false)) {
					SOAPLogin soapLogin = getBean(SOAPLoginDao.class).get(secureHash.toString());
					roomId = soapLogin.getRoomId();
					pp = pp.mergeWith(SwfPanel.addServer(roomId, false));
				}
				//TODO access denied
			} else if (!invitationHash.isEmpty()) {
				roomId = getBean(InvitationDao.class).getInvitationByHashCode(invitationHash.toString(), true).getRoom().getId();
			}
		} catch (Exception e) {
			//no-op
		}
		StringValue swfVal = pp.get("swf");
		PageParameters spp = new PageParameters(pp);
		if (roomId != null) {
			spp.mergeWith(new PageParameters().add(WICKET_ROOM_ID, roomId));
		}
		String swf = (swfVal.isEmpty() ? getFlashFile() : swfVal.toString())
				+ new PageParametersEncoder().encodePageParameters(spp);
		add(new Label("init", String.format("initSwf('%s');", swf)).setEscapeModelStrings(false));
		add(new AbstractAjaxTimerBehavior(Duration.minutes(5)) {
			private static final long serialVersionUID = 1L;

			@Override
			protected void onTimer(AjaxRequestTarget target) {
				getBean(SessiondataDao.class).checkSession(WebSession.getSid()); //keep SID alive
			}
		});
	}

	private static ResourceReference newResourceReference() {
		return new JavaScriptResourceReference(SwfPanel.class, "swf-functions.js");
	}
	
	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(new PriorityHeaderItem(JavaScriptHeaderItem.forReference(newResourceReference())));
		response.render(new PriorityHeaderItem(JavaScriptHeaderItem.forUrl("js/history.js")));
		response.render(new PriorityHeaderItem(JavaScriptHeaderItem.forUrl("js/openmeetings_functions.js")));
		response.render(new PriorityHeaderItem(CssHeaderItem.forUrl("css/history.css")));
		//FIXME TODO ugly HACK
		if (WebSession.get().getClientInfo().getProperties().isBrowserMozillaFirefox()) {
			response.render(new PriorityHeaderItem(CssHeaderItem.forCSS(".ui-widget-overlay{opacity: 1 !important;}", "ff-veil-hack")));
		}
	}

	private String getFlashFile() {
		return RuntimeConfigurationType.DEVELOPMENT == getApplication().getConfigurationType()
				? "maindebug.swf11.swf" : "main.swf11.swf";
	}
	
	private static PageParameters addServer(PageParameters pp, Server s) {
		return pp.add("protocol", s.getProtocol()).add("host", s.getAddress()).add("port", s.getPort()).add("context", s.getWebapp());
	}
	
	public static PageParameters addServer(Long roomId, boolean addBasic) {
		PageParameters pp = new PageParameters();
		if (addBasic) {
			pp.add("wicketsid", getSid()).add(WICKET_ROOM_ID, roomId).add("language", getLanguage());
		}
		List<Server> serverList = getBean(ServerDao.class).getActiveServers();

		long minimum = -1;
		Server result = null;
		HashMap<Server, List<Long>> activeRoomsMap = new HashMap<Server, List<Long>>();
		for (Server server : serverList) {
			List<Long> roomIds = getBean(SessionManager.class).getActiveRoomIdsByServer(server);
			if (roomIds.contains(roomId)) {
				// if the room is already opened on a server, redirect the user to that one,
				log.debug("Room is already opened on a server " + server.getAddress());
				return addServer(pp, server);
			}
			activeRoomsMap.put(server, roomIds);
		}
		for (Map.Entry<Server, List<Long>> entry : activeRoomsMap.entrySet()) {
			List<Long> roomIds = entry.getValue();
			long capacity = getBean(RoomDao.class).getRoomsCapacityByIds(roomIds);
			if (minimum < 0 || capacity < minimum) {
				minimum = capacity;
				result = entry.getKey();
			}
			log.debug("Checking server: " + entry.getKey() + " Number of rooms " + roomIds.size() + " RoomIds: "
					+ roomIds + " max(Sum): " + capacity);
		}
		return result == null ? pp : addServer(pp, result);
	}
}
