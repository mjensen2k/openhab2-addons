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
package org.openhab.binding.bondhome.internal.discovery;

import static org.eclipse.smarthome.core.thing.Thing.*;
import static org.openhab.binding.bondhome.internal.BondHomeBindingConstants.*;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.mdns.MDNSDiscoveryParticipant;
import org.eclipse.smarthome.config.discovery.mdns.internal.MDNSDiscoveryService;
import org.eclipse.smarthome.io.transport.mdns.MDNSClient;
import org.eclipse.smarthome.io.transport.mdns.internal.MDNSClientImpl;
import org.openhab.binding.bondhome.internal.api.BondHttpApi;
import org.openhab.binding.bondhome.internal.api.BondSysVersion;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class identifies Bond Bridges by their mDNS service information.
 *
 * @author Kai Kreuzer - Initial contribution
 */
@Component(service = MDNSDiscoveryParticipant.class, immediate = true)
@NonNullByDefault
public class BondMDNSDiscoveryParticipant implements MDNSDiscoveryParticipant {

    private final Logger logger = LoggerFactory.getLogger(BondMDNSDiscoveryParticipant.class);
    private static final String SERVICE_TYPE = "_bond._tcp.local.";// subtype jg7krkvzmja6

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return SUPPORTED_BRIDGE_TYPES;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public @Nullable ThingUID getThingUID(@Nullable ServiceInfo service) {
        if (service != null) {
            return new ThingUID(THING_TYPE_BOND_BRIDGE, service.getName());
        }
        return null;
    }

    @Override
    public @Nullable DiscoveryResult createResult(ServiceInfo service) {
        ThingUID thingUID = getThingUID(service);
        
        if (thingUID != null) {

            String hostAddress = "";
            BondHttpApi api = new BondHttpApi();
            BondSysVersion bsv;

            Map<String, Object> properties = new HashMap<>(2);
            properties.put(PROPERTY_SERIAL_NUMBER, service.getName());
            properties.put(CONFIG_BOND_ID, service.getName());

            InetAddress[] addresses = service.getInetAddresses();

            // discovery must include an ipAddress
            if (addresses.length > 0 && addresses[0] != null) {
                hostAddress = addresses[0].getHostAddress();
            }
            
            if(hostAddress.length() == 0) {
                // try host addresses
                String[] hostAddresses = service.getHostAddresses();
                if(hostAddresses.length > 0 && hostAddresses[0] != null) {
                    hostAddress = hostAddresses[0];
                }
            }

            if(hostAddress.length() == 0) {
                logger.debug("Discovered Bond Bridge (No Ip - passing): {}", service);
                return null;
            }

            logger.debug("Discovered Bond Bridge: {}", service);
            properties.put(CONFIG_IP_ADDRESS, hostAddress);

            final DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(thingUID)
                .withProperty(CONFIG_IP_ADDRESS, properties.get(CONFIG_IP_ADDRESS))
                .withThingType(THING_TYPE_BOND_BRIDGE).withLabel(BOND_BRIDGE_NAME)
                .withRepresentationProperty(CONFIG_BOND_ID)
                .build();

            return discoveryResult;
        }

        return null;
    }
}
