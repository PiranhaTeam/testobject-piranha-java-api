package org.testobject.piranha;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import jersey.repackaged.com.google.common.util.concurrent.ThreadFactoryBuilder;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.apache.commons.codec.binary.StringUtils;
import org.apache.log4j.Logger;
import org.glassfish.jersey.client.ClientProperties;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TestObjectPiranha {

	//	public static String TESTOBJECT_BASE_URL = "http://localhost:7070/";
    Logger logger = Logger.getLogger(TestObjectPiranha.class);
	public static String TESTOBJECT_APP_BASE_URL = "https://app.testobject.com:443/api/";
	public static String TESTOBJECT_LMI_BASE_URL = "https://lmi.testobject.com:443/api/";
	public static String TESTOBJECT_STAGE_APP_BASE_URL = "https://staging.testobject.org:443/api/";
	//	public static String TESTOBJECT_BASE_URL = "http://branches.testobject.org/api/";

	private final String baseUrl;
	private final Client client = ClientBuilder.newClient();
	private final WebTarget webTarget;
    private final boolean isVersion2;

	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
			new ThreadFactoryBuilder().setNameFormat("Piranha keep-alive").build());

	private String sessionId;
	private Proxy proxy;
	private int port;
	private String sessionInitResponse;
	private String liveViewURL;
	private String testReportURL;
    private DesiredCapabilities desiredCapabilities;

//    /**
//     * Constructor.
//     * @param desiredCapabilities
//     */
//    public TestObjectPiranha(DesiredCapabilities desiredCapabilities) {
//        this(TESTOBJECT_APP_BASE_URL, desiredCapabilities);
//    }

    /**
     * Constructor.
     * @param baseUrl
     * @param desiredCapabilities
     */
    public TestObjectPiranha(String baseUrl, DesiredCapabilities desiredCapabilities) {
        this.baseUrl = baseUrl;
        this.isVersion2 = isVersion2(desiredCapabilities); 
        this.webTarget = getWebTarget(baseUrl , desiredCapabilities);
        this.desiredCapabilities = desiredCapabilities;
        client.property(ClientProperties.CONNECT_TIMEOUT, 10 * 60 * 1000); // 10 minute
        client.property(ClientProperties.READ_TIMEOUT, 10 * 60 * 1000); // 10 minutes
    }

    /**
     * @param baseUrl
     * @return
     */
    private WebTarget getWebTarget(String baseUrl , DesiredCapabilities desiredCapabilities) {
        if (isVersion2(desiredCapabilities)) {
            return client.target(baseUrl + "piranha2");
        }
        return client.target(baseUrl + "piranha");
    }

    /**
     * @param desiredCapabilities
     * @return
     */
    private boolean isVersion2(DesiredCapabilities desiredCapabilities) {
        //note: this capability should be passed only for iOS Driver version2 
        if(desiredCapabilities.getCapabilities().containsKey("testobject_piranha_version")){
            int v = (int)desiredCapabilities.getCapabilities().get("testobject_piranha_version");
            if(v == 2){
                return true;
            }
        }
        return false;
    }

    /**
     * Open connection.
     */
    public void open() {
        logger.info(String.format("[%s] Opening TestObject Connection with webtarget : '%s'", Thread.currentThread().getName() , this.webTarget.getUri().toString()));
        Map<String, Map<String, Object>> fullCapabilities = new HashMap<String, Map<String, Object>>();
		fullCapabilities.put("desiredCapabilities", desiredCapabilities.getCapabilities());

		String capsAsJson = new GsonBuilder().create().toJson(fullCapabilities);

		try {
			String response = webTarget.path("session").request(MediaType.TEXT_PLAIN)
					.post(Entity.entity(capsAsJson, MediaType.APPLICATION_JSON), String.class);

			logger.info(String.format("response: %s" , response));
			Map<String, Object> map = jsonToMap(response);
			sessionId = (String) map.get("sessionId");
			if(sessionId == null){
			    sessionId = (String) map.get("sessionID");
			}
			liveViewURL = (String) map.get("testLiveViewUrl");
	         if(liveViewURL == null){
	             liveViewURL = (String) map.get("testobject_test_live_view_url");
	         }
            testReportURL = (String) map.get("testReportUrl");
            if (testReportURL == null) {
                testReportURL = (String) map.get("testobject_test_report_url");
            }
            setSessionInitResponse(response);

		} catch (InternalServerErrorException e) {
			rethrow(e);
		}
		//for v2 proxy is not started
		if(!isVersion2){
	        startProxyServer(sessionId);		    
		}
		startKeepAlive(sessionId);
    }

	private void startKeepAlive(final String sessionId) {
	    
	    logger.info(String.format("Starting Keep Alive for session: %s , with webTarget '%s'", 
	            sessionId , this.webTarget.getUri().toString()));
	    
	    Runnable runnable = new Runnable() {
            int c = 0;
            @Override
            public void run() {
                try {
                    webTarget.path("session").path(sessionId).path("keepalive")
                            .request(MediaType.APPLICATION_JSON)
                            .post(Entity.entity("", MediaType.APPLICATION_JSON), String.class);
                    c = 0;
                } catch (Exception e) {
                    logger.error(String.format("KeepAlive exception Occurred (try #%d) using webtarget '%s' error details are : %s",
                            c ,  webTarget.getUri().toString(),  e));
                    c = c + 1;
                    if(c > 6){
                        logger.error("Closing the testObjectSession : " + sessionId);
                        closeSilently();
                        throw e;
                    }
                }
            }
	    };    
		scheduler.scheduleAtFixedRate(runnable, 10, 10, TimeUnit.SECONDS);
	}

	private void startProxyServer(String sessionId) {
        port = findFreePort();

	    logger.info(String.format("Starting Proxy Server {port:%d} for session: %s , "
	            + "using uri : %s", 
	            port,
	            sessionId , 
	            this.webTarget.getUri().toString()));

		proxy = new Proxy(port, this.webTarget.getUri().toString(), sessionId);
		try {
			proxy.start();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public String getSessionId() {
		return sessionId;
	}

	private static int findFreePort() {
		int port;
		try {
			ServerSocket socket = new ServerSocket(0);
			port = socket.getLocalPort();
			socket.close();
		} catch (Exception e) {
			port = -1;
		}
		return port;
	}

	public int getPort() {
		return port;
	}

	private void rethrow(InternalServerErrorException e) {
		String response = e.getResponse().readEntity(String.class);

		throw new RuntimeException(response);
	}

	private static Map<String, Object> jsonToMap(String json) {
		Gson gson = new Gson();
		Type stringStringMap = new TypeToken<Map<String, Object>>() {
		}.getType();
		return gson.fromJson(json, stringStringMap);
	}

	public String getTestReportURL() {
		return testReportURL;
	}

	public String getLiveViewURL() {
		return liveViewURL;
	}
	
	
	/**
	 * Boolean flag to 
	 */
	private boolean isCloseCalled = false;

    /**
     * Close the connection.
     */
    public void close() {
        if (isCloseCalled) {
            return;
        }

        if (scheduler != null) {
            try {
                scheduler.shutdown();
            } catch (Throwable e) {
                logger.warn(String.format(
                        "Failed to shut down scheduler for session: %s. Error: %s", sessionId, e
                                .getMessage()));
            }
        }
        try {
            logger.info(String.format("Deleting session: %s", sessionId));
            deleteSession();
        } catch (Throwable e) {
            logger.warn(String.format("Failed to delete session: %s. Error: %s", sessionId, e
                    .getMessage()));
        }

        try {
            if(proxy != null){
                proxy.stop();                
            }
        } catch (Throwable e) {
            logger.warn(String.format("Failed to stop proxy for session: %s. Error: %s", sessionId,
                    e.getMessage()));
        }

        try {
            client.close();
        } catch (Throwable e) {
            logger.error(String.format("Failed to close the TestObject conection for session: %s",
                    sessionId));
        }
        isCloseCalled = true;
    }

    /**
     * Close the test object connection
     */
    public void closeSilently() {
        if (isCloseCalled) {
            return;
        }

        if (scheduler != null) {
            try {
                scheduler.shutdown();
            } catch (Throwable e) {
                // do nothing.
            }
        }
        try {
            deleteSession();
        } catch (Throwable e) {
            // do nothing.
        }

        try {
            proxy.stop();
        } catch (Throwable e) {
            // do nothing
        }
        try {
            client.close();
        } catch (Throwable e) {
            // do nothing.
        }
        isCloseCalled = true;
    }

    /**
     * Delete the session.
     */
    private void deleteSession() {
        if (sessionId == null || sessionId.trim().length() == 0) {
            return;
        }
        try {
            webTarget.path("session/" + sessionId).request(MediaType.APPLICATION_JSON).delete();
        } catch (InternalServerErrorException e) {
            rethrow(e);
        }
    }

	public String getSessionInitResponse() {
		return this.sessionInitResponse;
	}

	private void setSessionInitResponse(String sessionInitResponse) {
		this.sessionInitResponse = sessionInitResponse;
	}

	public static TestObjectApi api() {
		return new TestObjectApi(TESTOBJECT_LMI_BASE_URL);
	}

	public static TestObjectApi apiPublic() {
		return new TestObjectApi(TESTOBJECT_APP_BASE_URL);
	}

}
