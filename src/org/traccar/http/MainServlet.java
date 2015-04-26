/*
 * Copyright 2015 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.http;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.traccar.Context;
import org.traccar.GlobalTimer;
import org.traccar.database.DataCache;
import org.traccar.database.ObjectConverter;
import org.traccar.helper.Log;
import org.traccar.model.Position;

public class MainServlet extends HttpServlet {

    private static final long ASYNC_TIMEOUT = 120000;

    private static final String USER_ID = "userId";
    
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        String command = req.getPathInfo();

        if (command.equals("/async")) {
            async(req.startAsync());
        } else if (command.startsWith("/device")) {
            device(req, resp);
        } else if (command.equals("/login")) {
            login(req, resp);
        } else if (command.equals("/logout")) {
            logout(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }
    
    public class AsyncSession {
        
        private static final boolean DEBUG_ASYNC = true;
        
        private static final long SESSION_TIMEOUT = 30;
        private static final long REQUEST_TIMEOUT = 30;
        
        private boolean destroyed;
        private final long userId;
        private final List<Long> devices;
        private Timeout sessionTimeout;
        private Timeout requestTimeout;
        private final Map<Long, Position> positions = new HashMap<Long, Position>();
        private AsyncContext activeContext;
        
        private void logEvent(String message) {
            if (DEBUG_ASYNC) {
                Log.debug("AsyncSession: " + this.hashCode() + " destroyed: " + destroyed + " " + message);
            }
        }
        
        public AsyncSession(long userId, List<Long> devices) {
            logEvent("create userId: " + userId + " devices: " + devices.size());
            this.userId = userId;
            this.devices = devices;
        }

        @Override
        protected void finalize() throws Throwable {
            logEvent("finalize");
        }
        
        private final DataCache.DataCacheListener dataListener = new DataCache.DataCacheListener() {
            @Override
            public void onUpdate(Position position) {
                synchronized (AsyncSession.this) {
                    logEvent("onUpdate deviceId: " + position.getDeviceId());
                    if (!destroyed) {
                        if (requestTimeout != null) {
                            requestTimeout.cancel();
                            requestTimeout = null;
                        }
                        positions.put(position.getDeviceId(), position);
                        if (activeContext != null) {
                            response();
                        }
                    }
                }
            }
        };
        
        private final TimerTask sessionTimer = new TimerTask() {
            @Override
            public void run(Timeout tmt) throws Exception {
                synchronized (AsyncSession.this) {
                    logEvent("sessionTimeout");
                    Context.getDataCache().removeListener(devices, dataListener);
                    synchronized (asyncSessions) {
                        asyncSessions.remove(userId);
                    }
                    destroyed = true;
                }
            }
        };
                
        private final TimerTask requestTimer = new TimerTask() {
            @Override
            public void run(Timeout tmt) throws Exception {
                synchronized (AsyncSession.this) {
                    logEvent("requestTimeout");
                    if (!destroyed) {
                        if (activeContext != null) {
                            response();
                        }
                    }
                }
            }
        };
                
        public synchronized void init() {
            logEvent("init");
            Collection<Position> initialPositions = Context.getDataCache().getInitialState(devices);
            for (Position position : initialPositions) {
                positions.put(position.getDeviceId(), position);
            }
            
            Context.getDataCache().addListener(devices, dataListener);
        }
        
        public synchronized void request(AsyncContext context) {
            logEvent("request context: " + context.hashCode());
            if (!destroyed) {
                activeContext = context;
                if (sessionTimeout != null) {
                    sessionTimeout.cancel();
                    sessionTimeout = null;
                }

                if (!positions.isEmpty()) {
                    response();
                } else {
                    requestTimeout = GlobalTimer.getTimer().newTimeout(
                            requestTimer, REQUEST_TIMEOUT, TimeUnit.SECONDS);
                }
            }
        }
        
        private synchronized void response() {
            logEvent("response context: " + activeContext.hashCode());
            if (!destroyed) {
                ServletResponse response = activeContext.getResponse();

                JsonObjectBuilder result = Json.createObjectBuilder();
                result.add("success", true);
                result.add("data", ObjectConverter.convert(positions.values()));
                positions.clear();

                try {
                    response.getWriter().println(result.build().toString());
                } catch (IOException error) {
                    Log.warning(error);
                }

                activeContext.complete();
                activeContext = null;

                sessionTimeout = GlobalTimer.getTimer().newTimeout(
                        sessionTimer, SESSION_TIMEOUT, TimeUnit.SECONDS);
            }
        }
        
    }
    
    private final Map<Long, AsyncSession> asyncSessions = new HashMap<Long, AsyncSession>();
    
    private void async(final AsyncContext context) {
        
        context.setTimeout(60000);
        HttpServletRequest req = (HttpServletRequest) context.getRequest();
        long userId = (Long) req.getSession().getAttribute(USER_ID);
        
        synchronized (asyncSessions) {
            
            if (!asyncSessions.containsKey(userId)) {
                try {
                    List<Long> devices = Context.getDataManager().getDeviceList(userId);
                    asyncSessions.put(userId, new AsyncSession(userId, devices));
                } catch (SQLException error) {
                    Log.warning(error);
                }
                asyncSessions.get(userId).init();
            }
            
            asyncSessions.get(userId).request(context);
        }
    }
    
    private void device(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        
        long userId = (Long) req.getSession().getAttribute(USER_ID);
        
        JsonObjectBuilder result = Json.createObjectBuilder();
        
        try {
            result.add("success", true);
            result.add("data", Context.getDataManager().getDevices(userId));
        } catch(SQLException error) {
            result.add("success", false);
            result.add("error", error.getMessage());
        }
        
        resp.getWriter().println(result.build().toString());
    }

    private void login(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            req.getSession().setAttribute(USER_ID,
                    Context.getDataManager().login(req.getParameter("email"), req.getParameter("password")));
            resp.getWriter().println("{ success: true }");
        } catch (SQLException e) {
            throw new ServletException(e);
        }
    }

    private void logout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        req.getSession().removeAttribute(USER_ID);
        resp.getWriter().println("{ success: true }");
    }

}