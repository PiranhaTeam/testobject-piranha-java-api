package org.testobject.piranha;

import java.io.IOException;
import java.util.Scanner;

import javax.ws.rs.core.MediaType;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

public class Proxy extends NanoHTTPD {

	private final String url;
	private final CloseableHttpClient httpClient;
	Logger logger = Logger.getLogger(TestObjectPiranha.class);
	
	public Proxy(int port, String baseUrl, String sessionId) {
		super(port);

		this.httpClient = HttpClients.createDefault();
		this.url = baseUrl + "/session/" + sessionId;
	}

	@Override
	public Response serve(IHTTPSession session) {
		String command = session.readBody();

		HttpPost p = new HttpPost(url);
		p.setEntity(new StringEntity(command,
				ContentType.create(MediaType.APPLICATION_FORM_URLENCODED)));

		p.setHeader(HttpHeaders.ACCEPT_ENCODING, "application/json-rpc");
		
	    logger.debug("[" + Thread.currentThread().getName() + "] sending request " + url + " " + command);
		
		try {
			CloseableHttpResponse response = httpClient.execute(p);

			String responseAsString = entityToString(response);

			return new NanoHTTPD.Response(Response.Status.OK, "application/json-rpc", responseAsString);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String entityToString(CloseableHttpResponse response) {
		try {

			HttpEntity entity = response.getEntity();

			@SuppressWarnings("resource")
			String responseAsString = new Scanner(entity.getContent(), "UTF-8").useDelimiter("\\A").next();
			EntityUtils.consume(entity);
			return responseAsString;
		} catch (UnsupportedOperationException | IOException e) {
			throw new RuntimeException(e);
		} finally {
			try {
				response.close();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

}
