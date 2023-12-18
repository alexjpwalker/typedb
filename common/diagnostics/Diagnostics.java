/*
 * Copyright (C) 2022 Vaticle
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.vaticle.typedb.core.common.diagnostics;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class Diagnostics {

    private static final Logger LOG = LoggerFactory.getLogger(Diagnostics.class);
    public static final URI REPORTING_URI = URI.create("https://metric-api.eu.newrelic.com/metric/v1");
    private static final String API_KEY = "eu01xx8e758cbcf9c44ea97503b91b68FFFFNRAL";
    private static final String INSTANCE_ID = createInstanceId();

    public static void reportMetrics(Iterable<Metric> metrics) {
        try {
            JsonArray metricsJsonArray = new JsonArray();
            metrics.forEach(metric -> {
                JsonObject attrs = new JsonObject();
                attrs.add("instance.id", INSTANCE_ID);
                metric.attributes = attrs;
                metricsJsonArray.add(metric.toJson());
            });
            JsonObject metricsJsonObj = new JsonObject();
            metricsJsonObj.add("metrics", metricsJsonArray);
            JsonArray payload = new JsonArray();
            payload.add(metricsJsonObj);

            HttpClient client = HttpClient.newHttpClient();
            final HttpRequest request = HttpRequest.newBuilder().uri(REPORTING_URI)
                    .headers("Content-Type", "application/json", "Api-Key", API_KEY)
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // do nothing
        }
    }

    private static String createInstanceId() {
        try {
            byte[] mac = NetworkInterface.getByInetAddress(InetAddress.getLocalHost()).getHardwareAddress();
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(mac));
        } catch (NoSuchAlgorithmException | IOException e) {
            return "";
        }
    }

    public static class Metric {
        private final String name;
        private final String type;
        private final Object value;
        private final long timestamp;
        @Nullable
        private JsonObject attributes;

        public Metric(String name, String type, Object value, @Nullable JsonObject attributes) {
            this.name = name;
            this.type = type;
            this.value = value;
            this.timestamp = System.currentTimeMillis();
            this.attributes = attributes;
        }

        public JsonObject attributes() {
            return attributes;
        }

        public void attributes(JsonObject attributes) {
            this.attributes = attributes;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.add("name", name);
            json.add("type", type);

            if (value instanceof Long) json.add("value", (Long) value);
            else if (value instanceof Integer) json.add("value", (Integer) value);
            else if (value instanceof Double) json.add("value", (Double) value);
            else json.add("value", value.toString());

            json.add("timestamp", timestamp);
            if (attributes != null) json.add("attributes", attributes);
            return json;
        }
    }
}
