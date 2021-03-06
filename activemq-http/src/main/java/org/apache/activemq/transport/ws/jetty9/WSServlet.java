/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.transport.ws.jetty9;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.activemq.jms.pool.IntrospectionSupport;
import org.apache.activemq.transport.Transport;
import org.apache.activemq.transport.TransportAcceptListener;
import org.apache.activemq.transport.util.HttpTransportUtils;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

/**
 * Handle connection upgrade requests and creates web sockets
 */
public class WSServlet extends WebSocketServlet {

    private static final long serialVersionUID = -4716657876092884139L;

    private TransportAcceptListener listener;

    private final static Map<String, Integer> stompProtocols = new ConcurrentHashMap<> ();
    private final static Map<String, Integer> mqttProtocols = new ConcurrentHashMap<> ();

    private Map<String, Object> transportOptions;

    static {
        stompProtocols.put("v12.stomp", 3);
        stompProtocols.put("v11.stomp", 2);
        stompProtocols.put("v10.stomp", 1);
        stompProtocols.put("stomp", 0);

        mqttProtocols.put("mqttv3.1", 1);
        mqttProtocols.put("mqtt", 0);
    }

    @Override
    public void init() throws ServletException {
        super.init();
        listener = (TransportAcceptListener) getServletContext().getAttribute("acceptListener");
        if (listener == null) {
            throw new ServletException("No such attribute 'acceptListener' available in the ServletContext");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        getServletContext().getNamedDispatcher("default").forward(request, response);
    }

    @Override
    public void configure(WebSocketServletFactory factory) {
        factory.setCreator(new WebSocketCreator() {
            @Override
            public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp) {
                WebSocketListener socket;
                boolean isMqtt = false;
                for (String subProtocol : req.getSubProtocols()) {
                    if (subProtocol.startsWith("mqtt")) {
                        isMqtt = true;
                    }
                }
                if (isMqtt) {
                    socket = new MQTTSocket(HttpTransportUtils.generateWsRemoteAddress(req.getHttpServletRequest()));
                    resp.setAcceptedSubProtocol(getAcceptedSubProtocol(mqttProtocols,req.getSubProtocols(), "mqtt"));
                    ((MQTTSocket)socket).setTransportOptions(new HashMap(transportOptions));
                    ((MQTTSocket)socket).setPeerCertificates(req.getCertificates());
                } else {
                    socket = new StompSocket(HttpTransportUtils.generateWsRemoteAddress(req.getHttpServletRequest()));
                    ((StompSocket)socket).setCertificates(req.getCertificates());
                    resp.setAcceptedSubProtocol(getAcceptedSubProtocol(stompProtocols,req.getSubProtocols(), "stomp"));
                }
                listener.onAccept((Transport) socket);
                return socket;
            }
        });
    }

    private String getAcceptedSubProtocol(final Map<String, Integer> protocols,
            List<String> subProtocols, String defaultProtocol) {
        List<SubProtocol> matchedProtocols = new ArrayList<>();
        if (subProtocols != null && subProtocols.size() > 0) {
            //detect which subprotocols match accepted protocols and add to the list
            for (String subProtocol : subProtocols) {
                Integer priority = protocols.get(subProtocol);
                if(subProtocol != null && priority != null) {
                    //only insert if both subProtocol and priority are not null
                    matchedProtocols.add(new SubProtocol(subProtocol, priority));
                }
            }
            //sort the list by priority
            if (matchedProtocols.size() > 0) {
                Collections.sort(matchedProtocols, new Comparator<SubProtocol>() {
                    @Override
                    public int compare(SubProtocol s1, SubProtocol s2) {
                        return s2.priority.compareTo(s1.priority);
                    }
                });
                return matchedProtocols.get(0).protocol;
            }
        }
        return defaultProtocol;
    }

    private class SubProtocol {
        private String protocol;
        private Integer priority;
        public SubProtocol(String protocol, Integer priority) {
            this.protocol = protocol;
            this.priority = priority;
        }
    }

    public void setTransportOptions(Map<String, Object> transportOptions) {
        this.transportOptions = transportOptions;
    }
}
