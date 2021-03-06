package org.ow2.chameleon.rose.pubsubhubbub.publisher.internal;

import static org.ow2.chameleon.rose.pubsubhubbub.constants.PubsubhubbubConstants.HTTP_POST_HEADER_TYPE;
import static org.ow2.chameleon.rose.pubsubhubbub.constants.PubsubhubbubConstants.HTTP_POST_PARAMETER_HUB_MODE;
import static org.ow2.chameleon.rose.pubsubhubbub.constants.PubsubhubbubConstants.HTTP_POST_PARAMETER_RSS_TOPIC_URL;
import static org.ow2.chameleon.rose.pubsubhubbub.constants.PubsubhubbubConstants.DEFAULT_HTTP_PORT;
import static org.ow2.chameleon.rose.pubsubhubbub.constants.PubsubhubbubConstants.HTTP_POST_PARAMETER_MACHINEID;
import static org.ow2.chameleon.rose.pubsubhubbub.constants.PubsubhubbubConstants.HTTP_POST_PARAMETER_URL_CALLBACK;

import static org.osgi.service.log.LogService.LOG_INFO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.http.HttpService;
import org.osgi.service.log.LogService;
import org.ow2.chameleon.rose.RoseMachine;
import org.ow2.chameleon.rose.pubsubhubbub.constants.PubsubhubbubConstants.HubMode;

/**
 * Connect and register as a publisher to Rose Hub, sends an update and
 * unregister command.
 * 
 * @author Bartek
 * 
 */
public class HubPublisher {

	private final String rssUrl;
	private HttpPost postMethod;
	private final HttpClient client;
	private final String host;
	private final LogService logger;
	private final RoseMachine rose;
	private final String callbackUrl;

	/**
	 * Register a topic in hub.
	 * 
	 * @param urlHub
	 *            Pubsubhubbub url
	 * @param pRssUrl
	 *            RSS topic url
	 * @param context
	 *            BundleContext
	 * @param pRose
	 *            Rose service
	 * @param pLogger
	 *            Log service
	 * @throws IOException
	 *             exception
	 */
	public HubPublisher(final String urlHub, final String pRssUrl,
			final String pCallbackUrl, final BundleContext context,
			final RoseMachine pRose, final LogService pLogger)
			throws IOException {
		String port = null;

		this.logger = pLogger;
		this.rose = pRose;
		final ServiceReference httpServiceRef = context
				.getServiceReference(HttpService.class.getName());
		if (httpServiceRef != null) {
			port = (String) httpServiceRef
					.getProperty("org.osgi.service.http.port");

		}
		if (port == null) {
			port = context.getProperty("org.osgi.service.http.port");
		}

		// set default port number
		if (port == null) {
			port = DEFAULT_HTTP_PORT;
		}

		this.host = rose.getHost();

		this.rssUrl = "http://" + this.host + ":" + port + pRssUrl + "/";
		callbackUrl = "http://" + this.host + ":" + port + pCallbackUrl;
		client = new DefaultHttpClient();

		// prepare post method
		postMethod = new HttpPost(urlHub);
		postMethod.setHeader("Content-Type", HTTP_POST_HEADER_TYPE);

		final List<NameValuePair> nvps = new ArrayList<NameValuePair>();
		nvps.add(new BasicNameValuePair(HTTP_POST_PARAMETER_HUB_MODE,
				HubMode.publish.toString()));
		nvps.add(new BasicNameValuePair(HTTP_POST_PARAMETER_RSS_TOPIC_URL,
				this.rssUrl));
		nvps.add(new BasicNameValuePair(HTTP_POST_PARAMETER_MACHINEID, rose
				.getId()));
		nvps.add(new BasicNameValuePair(HTTP_POST_PARAMETER_URL_CALLBACK,
				this.callbackUrl));

		postMethod.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
		final HttpResponse response = client.execute(postMethod);
		if (response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
			response.getEntity().getContent().close();
			throw new ClientProtocolException(
					"Server didn register a topic, received status from hub: "
							+ response.getStatusLine().getStatusCode());
		}
		// read an empty entity and close a connection
		response.getEntity().getContent().close();
		logger.log(LOG_INFO, "Publisher successfully registered");
	}

	/**
	 * Send an update to hub.
	 * 
	 * @param connectedHubs
	 * 
	 */
	public final void update(Set<String> connectedHubs) {

		for (String hubUrl : connectedHubs) {

			// prepare post method
			postMethod = new HttpPost(hubUrl);
			postMethod.setHeader("Content-Type",
					"application/x-www-form-urlencoded");
			try {
				final List<NameValuePair> nvps = new ArrayList<NameValuePair>();
				nvps.add(new BasicNameValuePair(HTTP_POST_PARAMETER_HUB_MODE,
						HubMode.update.toString()));
				nvps.add(new BasicNameValuePair(
						HTTP_POST_PARAMETER_RSS_TOPIC_URL, this.rssUrl));
				postMethod
						.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
				final HttpResponse response = client.execute(postMethod);
				if (response.getStatusLine().getStatusCode() != HttpStatus.SC_ACCEPTED) {
					response.getEntity().getContent().close();
					throw new ClientProtocolException(
							"Server didnt update, received status from hub: "
									+ response.getStatusLine().getStatusCode());
				}
				// read an empty entity and close a connection
				response.getEntity().getContent().close();
			} catch (Exception e) {
				logger.log(LogService.LOG_ERROR, "Error in update", e);
			}
		}

	}

	/**
	 * Send an unregister to hub.
	 * 
	 * @param connectedHubs
	 */
	public final void unregister(Set<String> connectedHubs) {

		for (String hubUrl : connectedHubs) {
			// prepare post method
			postMethod = new HttpPost(hubUrl);
			postMethod.setHeader("Content-Type",
					"application/x-www-form-urlencoded");
			try {
				List<NameValuePair> nvps = new ArrayList<NameValuePair>();
				nvps.add(new BasicNameValuePair(HTTP_POST_PARAMETER_HUB_MODE,
						HubMode.unpublish.toString()));
				nvps.add(new BasicNameValuePair(
						HTTP_POST_PARAMETER_RSS_TOPIC_URL, this.rssUrl));
				nvps.add(new BasicNameValuePair(HTTP_POST_PARAMETER_MACHINEID,
						rose.getId()));
				postMethod
						.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
				HttpResponse response = client.execute(postMethod);
				if (response.getStatusLine().getStatusCode() != HttpStatus.SC_ACCEPTED) {
					response.getEntity().getContent().close();
					throw new ClientProtocolException(
							"Server didnt unregister, received status from hub: "
									+ response.getStatusLine().getStatusCode());
				}
				// read an empty entity and close a connection
				response.getEntity().getContent().close();
			} catch (Exception e) {
				logger.log(LogService.LOG_ERROR, "Error in unregister", e);
			}
		}
	}
}
