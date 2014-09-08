package org.entcore.timeline;

import fr.wseduc.webutils.Server;
import org.entcore.timeline.controllers.TimelineController;

public class Timeline extends Server {

	@Override
	public void start() {
		super.start();
		TimelineController timeline = new TimelineController(vertx, container, rm, securedActions);

		timeline.get("/timeline", "view");
		timeline.get("/calendar", "calendar");
		timeline.get("/lastNotifications", "lastEvents");
		timeline.get("/i18nNotifications", "i18n");
		timeline.get("/types", "listTypes");

		try {
			timeline.registerMethod(config.getString("address"), "busApi");
		} catch (NoSuchMethodException | IllegalAccessException e) {
			log.error(e.getMessage(), e);
		}

	}

}