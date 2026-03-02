/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Lennart Martens
 */

package netzbegruenung.keycloak.authenticator.gateway;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

/**
 * SMS Service implementation for BudgetSMS (https://www.budgetsms.net/)
 * API Documentation: https://www.budgetsms.net/sms-http-api/send-sms/
 */
public class BudgetSmsService implements SmsService {

	private static final Logger logger = Logger.getLogger(BudgetSmsService.class);
	private static final String API_ENDPOINT_LIVE = "https://api.budgetsms.net/sendsms/";
	private static final String API_ENDPOINT_TEST = "https://api.budgetsms.net/testsms/";
	private static final Pattern PLUS_PREFIX_PATTERN = Pattern.compile("^\\+");
	private static final Pattern DOUBLE_ZERO_PREFIX_PATTERN = Pattern.compile("^00");

	private final String username;
	private final String userid;
	private final String handle;
	private final String senderId;
	private final boolean testMode;

	/**
	 * Creates a new BudgetSmsService instance.
	 *
	 * @param config Configuration map containing:
	 *               - budgetsms.username: BudgetSMS username (required)
	 *               - budgetsms.userid: BudgetSMS userid (required)
	 *               - budgetsms.handle: BudgetSMS handle (required)
	 *               - budgetsms.testmode: Use test API (optional, default false)
	 *               - senderId: SMS sender ID (required)
	 */
	BudgetSmsService(Map<String, String> config) {
		this.username = config.get("budgetsms.username");
		this.userid = config.get("budgetsms.userid");
		this.handle = config.get("budgetsms.handle");
		this.testMode = Boolean.parseBoolean(config.getOrDefault("budgetsms.testmode", "false"));
		this.senderId = config.get("senderId");

		if (username == null || username.isEmpty()) {
			throw new IllegalArgumentException("BudgetSMS username is required");
		}
		if (userid == null || userid.isEmpty()) {
			throw new IllegalArgumentException("BudgetSMS userid is required");
		}
		if (handle == null || handle.isEmpty()) {
			throw new IllegalArgumentException("BudgetSMS handle is required");
		}
		if (senderId == null || senderId.isEmpty()) {
			throw new IllegalArgumentException("BudgetSMS senderId is required");
		}
	}

	@Override
	public void send(String phoneNumber, String message) {
		String formattedPhone = formatPhoneNumber(phoneNumber);

		String url = buildRequestUrl(formattedPhone, message);

		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.GET()
				.build();

		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			int statusCode = response.statusCode();
			String responseBody = response.body();

			if (statusCode >= 200 && statusCode < 300 && isSuccessResponse(responseBody)) {
				logger.infof("Sent SMS to %s via BudgetSMS%s. Response: %s", formattedPhone, testMode ? " (TEST MODE)" : "", responseBody);
			} else {
				logger.errorf("Failed to send SMS to %s via BudgetSMS%s. Status: %d, Response: %s",
						formattedPhone, testMode ? " (TEST MODE)" : "", statusCode, responseBody);
			}
		} catch (Exception e) {
			logger.errorf(e, "Failed to send SMS to %s via BudgetSMS%s", formattedPhone, testMode ? " (TEST MODE)" : "");
		}
	}

	/**
	 * Builds the request URL with all required parameters.
	 */
	private String buildRequestUrl(String phoneNumber, String message) {
		String endpoint = testMode ? API_ENDPOINT_TEST : API_ENDPOINT_LIVE;
		StringBuilder url = new StringBuilder(endpoint);
		url.append("?username=").append(encode(username));
		url.append("&userid=").append(encode(userid));
		url.append("&handle=").append(encode(handle));
		url.append("&from=").append(encode(senderId));
		url.append("&to=").append(encode(phoneNumber));
		url.append("&msg=").append(encode(message));

		return url.toString();
	}

	/**
	 * Formats phone number to E.164 format without leading + or 00.
	 * BudgetSMS requires numbers like: 31612345678 (not +31612345678 or 0031612345678)
	 */
	private String formatPhoneNumber(String phoneNumber) {
		if (phoneNumber == null) {
			return "";
		}

		// Remove any spaces, dashes, or parentheses
		String cleaned = phoneNumber.replaceAll("[\\s\\-\\(\\)]", "");

		// Remove leading + if present
		cleaned = PLUS_PREFIX_PATTERN.matcher(cleaned).replaceFirst("");

		// Remove leading 00 if present
		cleaned = DOUBLE_ZERO_PREFIX_PATTERN.matcher(cleaned).replaceFirst("");

		return cleaned;
	}

	/**
	 * URL encodes a string value.
	 */
	private String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/**
	 * Checks if the response indicates success.
	 * BudgetSMS returns "OK" followed by message ID on success.
	 * Error responses start with "ERR".
	 */
	private boolean isSuccessResponse(String response) {
		return response != null && response.trim().toUpperCase().startsWith("OK");
	}
}
