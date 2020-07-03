/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.bondhome.internal.handler;

import static org.eclipse.smarthome.core.thing.Thing.*;
import static org.openhab.binding.bondhome.internal.BondHomeBindingConstants.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.californium.scandium.ConnectionListener;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.common.ThreadPoolManager;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.BridgeHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.bondhome.internal.api.BPUPListener;
import org.openhab.binding.bondhome.internal.api.BPUPUpdate;
import org.openhab.binding.bondhome.internal.api.BondBridgeState;
import org.openhab.binding.bondhome.internal.api.BondDeviceState;
import org.openhab.binding.bondhome.internal.api.BondHttpApi;
import org.openhab.binding.bondhome.internal.api.BondSysVersion;
import org.openhab.binding.bondhome.internal.config.BondBridgeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BondBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Sara Geleskie Damiano - Initial contribution
 */
@NonNullByDefault
public class BondBridgeHandler extends BaseBridgeHandler implements Bridge {

    private final Logger logger = LoggerFactory.getLogger(BondBridgeHandler.class);

    // Get a dedicated threadpool for the long-running listener thread.
    // Intent is to not permanently tie up the common scheduler pool.
    private final ScheduledExecutorService bondScheduler = ThreadPoolManager.getScheduledPool("bondBridgeHandler");
    private @Nullable ScheduledFuture<?> listenerJob;
    private final BPUPListener udpListener;
    private final BondHttpApi api;

    private @NonNullByDefault({}) BondBridgeConfiguration config;

    private final Set<BondDeviceHandler> handlers = Collections.synchronizedSet(new HashSet<>());

    public BondBridgeHandler(Bridge bridge) {
        super(bridge);
        udpListener = new BPUPListener(this);
        api = new BondHttpApi(this);
        logger.debug("Created a BondBridgeHandler for thing '{}'", getThing().getUID());
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Not needed, all commands are handled in the {@link BondDeviceHandler}
    }

    @Override
    public void initialize() {
        logger.debug("Start initializing the Bond bridge!");
        config = getConfigAs(BondBridgeConfiguration.class);

        // set the thing status to UNKNOWN temporarily
        updateStatus(ThingStatus.UNKNOWN);

        // Example for background initialization:
        scheduler.execute(() -> {
            initializeThing();
        });
    }

    private void initializeThing() {
        config = getConfigAs(BondBridgeConfiguration.class);
        if (config != null) {
            if (config.bridgeIP == null) {
                try {
                    logger.trace("IP address of Bond {} is unknown", config.bondId);

                    String lookupAddress = config.bondId + ".local";
                    logger.trace("Attempting to get IP address for Bond Bridge {}", lookupAddress);

                    InetAddress ia = InetAddress.getByName(lookupAddress);
                    String ip = ia.getHostAddress();

                    Configuration c = editConfiguration();
                    c.put(CONFIG_IP_ADDRESS, ip);

                    updateConfiguration(c);
                } catch (UnknownHostException ignored) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Unable to get an IP Address for Bond Bridge");
                    return;
                }
            } else {
                try {
                    InetAddress.getByName(config.bridgeIP);
                } catch (UnknownHostException ignored) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "IP Address or host name for Bond Bridge is not valid");
                    return; // BUG? Without this, the initialize routine will fall through to attempt to
                            // updateBridgeProperties without a valid address
                }
            }
        }

        // Ask the bridge it's current status and update the properties with the sys info
        // and subsequently ask the bridge it's current configuration for prepopulating name/location
        // This will also set the thing status to online/offline based on whether it
        // succeeds in getting the properties from the bridge.
        updateBridgeProperties();

        // Finish
        logger.debug("Finished initializing Bond bridge - starting device discovery!");
    }

    @Override
    public void dispose() {
        logger.trace("Disposing Bond Bridge handler");
        // The listener should already have been stopped when the last child was disposed,
        // but we'll call the stop here for good measure.
        stopUDPListenerJob();
    }

    private synchronized void startUDPListenerJob() {
        logger.debug("Scheduled listener job to start in 30 seconds");
        listenerJob = bondScheduler.schedule(udpListener, 30, TimeUnit.SECONDS);
    }

    private synchronized void stopUDPListenerJob() {
        logger.trace("Stopping UDP listener job");
        ScheduledFuture<?> lJob = this.listenerJob;
        if (lJob != null) {
            lJob.cancel(true);
            udpListener.shutdown();
            logger.debug("UDP listener job stopped");
        }
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        super.childHandlerInitialized(childHandler, childThing);
        if (childHandler instanceof BondDeviceHandler) {
            BondDeviceHandler handler = (BondDeviceHandler) childHandler;
            synchronized (handlers) {
                if (handlers.isEmpty()) {
                    // Start the BPUP update service after the first child device is added
                    startUDPListenerJob();
                }
                if (!handlers.contains(handler)) {
                    handlers.add(handler);
                }
            }
        }
    }

    @Override
    public void childHandlerDisposed(ThingHandler childHandler, Thing childThing) {
        if (childHandler instanceof BondDeviceHandler) {
            BondDeviceHandler handler = (BondDeviceHandler) childHandler;
            synchronized (handlers) {
                if (handlers.contains(handler)) {
                    handlers.remove(handler);
                }
                if (handlers.isEmpty()) {
                    // Stop the update service when the last child is removed
                    stopUDPListenerJob();
                }
            }
        }
        super.childHandlerDisposed(childHandler, childThing);
    }

    /**
     * Forwards a push update to a device
     *
     * @param the {@link BPUPUpdate object}
     */
    public void forwardUpdateToThing(BPUPUpdate pushUpdate) {
        BondDeviceState updateState = pushUpdate.deviceState;
        String topic = pushUpdate.topic;
        String deviceId = null;
        if (topic != null) {
            deviceId = topic.split("/")[1];
        }
        // We can't use getThingByUID because we don't know the type of device and thus
        // don't know the full uid (that is we cannot tell a fan from a fireplace, etc,
        // from the contents of the update)
        if (deviceId != null) {
            synchronized (handlers) {
                for (BondDeviceHandler handler : handlers) {
                    String handlerDeviceId = handler.getDeviceId();
                    if (handlerDeviceId.equalsIgnoreCase(deviceId)) {
                        handler.updateChannelsFromState(updateState);
                        break;
                    }
                }
            }
        } else {
            logger.warn("Can not read device Id from push update.");
        }
    }

    /**
     * Returns the Id of the bridge associated with the handler
     */
    public String getBridgeId() {
        BondBridgeConfiguration config = getConfigAs(BondBridgeConfiguration.class);
        return config.bondId;
    }

    /**
     * Returns the Ip Address of the bridge associated with the handler as a string
     */
    public @Nullable String getBridgeIpAddress() {
        BondBridgeConfiguration config = getConfigAs(BondBridgeConfiguration.class);
        return config.bridgeIP;
    }

    /**
     * Returns the local token of the bridge associated with the handler as a string
     */
    public @Nullable String getBridgeToken() {
        BondBridgeConfiguration config = getConfigAs(BondBridgeConfiguration.class);
        return config.localToken;
    }

    /**
     * Returns the api instance
     */
    public BondHttpApi getBridgeAPI() {
        return this.api;
    }

    /**
     * Set the bridge status offline.
     *
     * Called by the dependents to set the bridge offline when repeated requests fail.
     *
     * NOTE: This does NOT stop the UDP listener job, which will keep pinging the
     * bridge's IP once a minute. The listener job will set the bridge back online
     * if it receives a proper response from the bridge.
     */
    public void setBridgeOffline(ThingStatusDetail detail, String description) {
        updateStatus(ThingStatus.OFFLINE, detail, description);
    }

    /**
     * Set the bridge status back online.
     *
     * Called by the UDP listener when it gets a proper response.
     */
    public void setBridgeOnline() {
        updateStatus(ThingStatus.ONLINE);
        updateBridgeProperties();
    }

    private void updateBridgeProperties() {
        BondSysVersion myVersion = null;
        BondBridgeState myState = null;

        try {
            myVersion = api.getBridgeVersion();
            myState = api.getBridgeConfig();
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Unable to access Bond local API through bridge");
        }

        if (myVersion != null) {
            // Update all the thing properties based on the result
            Map<String, String> thingProperties = new HashMap<String, String>();
            thingProperties.put(PROPERTY_VENDOR, myVersion.make);
            thingProperties.put(PROPERTY_MODEL_ID, myVersion.model);
            thingProperties.put(PROPERTY_SERIAL_NUMBER, myVersion.bondid);
            thingProperties.put(PROPERTY_FIRMWARE_VERSION, myVersion.firmwareVersion);
            updateProperties(thingProperties);
            updateStatus(ThingStatus.ONLINE);
        }

        if (myState != null) {
            // Update the bridge state properties based on the result of it's respective call
            this.getThing().setLocation(myState.location);
            this.getThing().setLabel(myState.name);
        }

        if (myVersion == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Unable get Bond bridge version via API");
        }
    }

    @Override
    public @Nullable String getLabel() {
        return this.getThing().getLabel();
    }

    @Override
    public void setLabel(@Nullable String label) {
        this.getThing().setLabel(label);
    }

    @Override
    public List<Channel> getChannels() {
        return this.getThing().getChannels();
    }

    @Override
    public List<Channel> getChannelsOfGroup(String channelGroupId) {
        return this.getThing().getChannelsOfGroup(channelGroupId);
    }

    @Override
    public @Nullable Channel getChannel(String channelId) {
        return this.getThing().getChannel(channelId);
    }

    @Override
    public @Nullable Channel getChannel(ChannelUID channelUID) {
        return this.getThing().getChannel(channelUID);
    }

    @Override
    public ThingStatus getStatus() {
        return this.getThing().getStatus();
    }

    @Override
    public ThingStatusInfo getStatusInfo() {
        return this.getThing().getStatusInfo();
    }

    @Override
    public void setStatusInfo(ThingStatusInfo status) {
        this.getThing().setStatusInfo(status);
    }

    @Override
    public void setHandler(@Nullable ThingHandler thingHandler) {
        this.getThing().setHandler(thingHandler);
    }

    @Override
    public @Nullable ThingUID getBridgeUID() {
        return this.getThing().getBridgeUID();
    }

    @Override
    public void setBridgeUID(@Nullable ThingUID bridgeUID) {
        this.getThing().setBridgeUID(bridgeUID);
    }

    @Override
    public Configuration getConfiguration() {
        return this.getThing().getConfiguration();
    }

    @Override
    public ThingUID getUID() {
        return this.getThing().getUID();
    }

    @Override
    public ThingTypeUID getThingTypeUID() {
        return this.getThing().getThingTypeUID();
    }

    @Override
    public Map<String, String> getProperties() {
        return this.getThing().getProperties();
    }

    @Override
    public @Nullable String setProperty(String name, @Nullable String value) {
        return this.getThing().setProperty(name, value);
    }

    @Override
    public void setProperties(Map<String, String> properties) {
        this.getThing().setProperties(properties);
    }

    @Override
    public @Nullable String getLocation() {
        return this.getThing().getLocation();
    }

    @Override
    public void setLocation(@Nullable String location) {
        this.getThing().setLocation(location);
    }

    @Override
    public boolean isEnabled() {
        return this.getThing().isEnabled();
    }

    @Override
    public @Nullable Thing getThing(ThingUID thingUID) {
        return this.getThing().getThing(thingUID);
    }

    @Override
    public List<Thing> getThings() {
        return this.getThing().getThings();
    }

    @Override
    public @Nullable BridgeHandler getHandler() {
        return this.getThing().getHandler();
    }
}
