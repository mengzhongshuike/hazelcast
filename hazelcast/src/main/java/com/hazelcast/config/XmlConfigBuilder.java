/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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
 */

package com.hazelcast.config;

import com.hazelcast.config.CacheSimpleConfig.ExpiryPolicyFactoryConfig;
import com.hazelcast.config.CacheSimpleConfig.ExpiryPolicyFactoryConfig.DurationConfig;
import com.hazelcast.config.CacheSimpleConfig.ExpiryPolicyFactoryConfig.TimedExpiryPolicyFactoryConfig;
import com.hazelcast.config.CacheSimpleConfig.ExpiryPolicyFactoryConfig.TimedExpiryPolicyFactoryConfig.ExpiryPolicyType;
import com.hazelcast.config.EvictionConfig.MaxSizePolicy;
import com.hazelcast.config.LoginModuleConfig.LoginModuleUsage;
import com.hazelcast.config.PartitionGroupConfig.MemberGroupType;
import com.hazelcast.config.PermissionConfig.PermissionType;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.internal.eviction.impl.EvictionConfigHelper;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.map.eviction.MapEvictionPolicy;
import com.hazelcast.mapreduce.TopologyChangedStrategy;
import com.hazelcast.nio.ClassLoaderUtil;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.quorum.QuorumType;
import com.hazelcast.spi.ServiceConfigurationParser;
import com.hazelcast.topic.TopicOverloadPolicy;
import com.hazelcast.util.ExceptionUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.config.MapStoreConfig.InitialLoadMode;
import static com.hazelcast.config.XmlElements.CACHE;
import static com.hazelcast.config.XmlElements.EXECUTOR_SERVICE;
import static com.hazelcast.config.XmlElements.GROUP;
import static com.hazelcast.config.XmlElements.HOT_RESTART_PERSISTENCE;
import static com.hazelcast.config.XmlElements.IMPORT;
import static com.hazelcast.config.XmlElements.INSTANCE_NAME;
import static com.hazelcast.config.XmlElements.JOB_TRACKER;
import static com.hazelcast.config.XmlElements.LICENSE_KEY;
import static com.hazelcast.config.XmlElements.LIST;
import static com.hazelcast.config.XmlElements.LISTENERS;
import static com.hazelcast.config.XmlElements.LITE_MEMBER;
import static com.hazelcast.config.XmlElements.MANAGEMENT_CENTER;
import static com.hazelcast.config.XmlElements.MAP;
import static com.hazelcast.config.XmlElements.MEMBER_ATTRIBUTES;
import static com.hazelcast.config.XmlElements.MULTIMAP;
import static com.hazelcast.config.XmlElements.NATIVE_MEMORY;
import static com.hazelcast.config.XmlElements.NETWORK;
import static com.hazelcast.config.XmlElements.PARTITION_GROUP;
import static com.hazelcast.config.XmlElements.PROPERTIES;
import static com.hazelcast.config.XmlElements.QUEUE;
import static com.hazelcast.config.XmlElements.QUORUM;
import static com.hazelcast.config.XmlElements.RELIABLE_TOPIC;
import static com.hazelcast.config.XmlElements.REPLICATED_MAP;
import static com.hazelcast.config.XmlElements.RINGBUFFER;
import static com.hazelcast.config.XmlElements.SECURITY;
import static com.hazelcast.config.XmlElements.SEMAPHORE;
import static com.hazelcast.config.XmlElements.SERIALIZATION;
import static com.hazelcast.config.XmlElements.SERVICES;
import static com.hazelcast.config.XmlElements.SET;
import static com.hazelcast.config.XmlElements.TOPIC;
import static com.hazelcast.config.XmlElements.WAN_REPLICATION;
import static com.hazelcast.config.XmlElements.canOccurMultipleTimes;
import static com.hazelcast.util.Preconditions.checkHasText;
import static com.hazelcast.util.Preconditions.checkNotNull;
import static com.hazelcast.util.StringUtil.LINE_SEPARATOR;
import static com.hazelcast.util.StringUtil.isNullOrEmpty;
import static com.hazelcast.util.StringUtil.lowerCaseInternal;
import static com.hazelcast.util.StringUtil.upperCaseInternal;
import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;

/**
 * A XML {@link ConfigBuilder} implementation.
 */
public class XmlConfigBuilder extends AbstractConfigBuilder implements ConfigBuilder {

    private static final int THOUSAND_FACTOR = 5;

    private static final ILogger LOGGER = Logger.getLogger(XmlConfigBuilder.class);

    private final Set<String> occurrenceSet = new HashSet<String>();
    private final InputStream in;

    private Properties properties = System.getProperties();
    private File configurationFile;
    private URL configurationUrl;
    private Config config;

    /**
     * Constructs a XmlConfigBuilder that reads from the provided XML file.
     *
     * @param xmlFileName the name of the XML file that the XmlConfigBuilder reads from
     * @throws FileNotFoundException if the file can't be found.
     */
    public XmlConfigBuilder(String xmlFileName) throws FileNotFoundException {
        this(new FileInputStream(xmlFileName));
        this.configurationFile = new File(xmlFileName);
    }

    /**
     * Constructs a XmlConfigBuilder that reads from the given InputStream.
     *
     * @param inputStream the InputStream containing the XML configuration.
     * @throws IllegalArgumentException if inputStream is null.
     */
    public XmlConfigBuilder(InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream can't be null");
        }
        this.in = inputStream;
    }

    /**
     * Constructs a XMLConfigBuilder that reads from the given URL.
     *
     * @param url the given url that the XMLConfigBuilder reads from
     * @throws IOException if URL is invalid
     */
    public XmlConfigBuilder(URL url) throws IOException {
        checkNotNull(url, "URL is null!");
        this.in = url.openStream();
        this.configurationUrl = url;
    }

    /**
     * Constructs a XmlConfigBuilder that tries to find a usable XML configuration file.
     */
    public XmlConfigBuilder() {
        XmlConfigLocator locator = new XmlConfigLocator();
        this.in = locator.getIn();
        this.configurationFile = locator.getConfigurationFile();
        this.configurationUrl = locator.getConfigurationUrl();
    }

    /**
     * Gets the current used properties. Can be null if no properties are set.
     *
     * @return the current used properties.
     * @see #setProperties(java.util.Properties)
     */
    @Override
    public Properties getProperties() {
        return properties;
    }

    /**
     * Sets the used properties. Can be null if no properties should be used.
     * <p/>
     * Properties are used to resolve ${variable} occurrences in the XML file.
     *
     * @param properties the new properties.
     * @return the XmlConfigBuilder
     */
    public XmlConfigBuilder setProperties(Properties properties) {
        this.properties = properties;
        return this;
    }

    @Override
    protected ConfigType getXmlType() {
        return ConfigType.SERVER;
    }

    @Override
    public Config build() {
        return build(new Config());
    }

    Config build(Config config) {
        config.setConfigurationFile(configurationFile);
        config.setConfigurationUrl(configurationUrl);
        try {
            parseAndBuildConfig(config);
        } catch (Exception e) {
            throw ExceptionUtil.rethrow(e);
        }
        return config;
    }

    private void parseAndBuildConfig(Config config) throws Exception {
        this.config = config;
        Document doc = parse(in);
        Element root = doc.getDocumentElement();
        try {
            root.getTextContent();
        } catch (Throwable e) {
            domLevel3 = false;
        }
        process(root);
        schemaValidation(root.getOwnerDocument());
        handleConfig(root);
    }

    @Override
    protected Document parse(InputStream is) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        Document doc;
        try {
            doc = builder.parse(is);
        } catch (Exception e) {
            if (configurationFile != null) {
                String msg = "Failed to parse " + configurationFile
                        + LINE_SEPARATOR + "Exception: " + e.getMessage()
                        + LINE_SEPARATOR + "Hazelcast startup interrupted.";
                LOGGER.severe(msg);

            } else if (configurationUrl != null) {
                String msg = "Failed to parse " + configurationUrl
                        + LINE_SEPARATOR + "Exception: " + e.getMessage()
                        + LINE_SEPARATOR + "Hazelcast startup interrupted.";
                LOGGER.severe(msg);
            } else {
                String msg = "Failed to parse the inputstream"
                        + LINE_SEPARATOR + "Exception: " + e.getMessage()
                        + LINE_SEPARATOR + "Hazelcast startup interrupted.";
                LOGGER.severe(msg);
            }
            throw new InvalidConfigurationException(e.getMessage(), e);
        } finally {
            IOUtil.closeResource(is);
        }
        return doc;
    }

    private void handleConfig(Element docElement) throws Exception {
        for (Node node : childElements(docElement)) {
            String nodeName = cleanNodeName(node);
            if (occurrenceSet.contains(nodeName)) {
                throw new InvalidConfigurationException(
                        "Duplicate '" + nodeName + "' definition found in XML configuration.");
            }
            if (handleXmlNode(node, nodeName)) {
                continue;
            }
            if (!canOccurMultipleTimes(nodeName)) {
                occurrenceSet.add(nodeName);
            }
        }
    }

    private boolean handleXmlNode(Node node, String nodeName) throws Exception {
        if (INSTANCE_NAME.isEqual(nodeName)) {
            handleInstanceName(node);
        } else if (NETWORK.isEqual(nodeName)) {
            handleNetwork(node);
        } else if (IMPORT.isEqual(nodeName)) {
            throw new HazelcastException("Non-expanded <import> element found");
        } else if (GROUP.isEqual(nodeName)) {
            handleGroup(node);
        } else if (PROPERTIES.isEqual(nodeName)) {
            fillProperties(node, config.getProperties());
        } else if (WAN_REPLICATION.isEqual(nodeName)) {
            handleWanReplication(node);
        } else if (EXECUTOR_SERVICE.isEqual(nodeName)) {
            handleExecutor(node);
        } else if (SERVICES.isEqual(nodeName)) {
            handleServices(node);
        } else if (QUEUE.isEqual(nodeName)) {
            handleQueue(node);
        } else if (MAP.isEqual(nodeName)) {
            handleMap(node);
        } else if (MULTIMAP.isEqual(nodeName)) {
            handleMultiMap(node);
        } else if (REPLICATED_MAP.isEqual(nodeName)) {
            handleReplicatedMap(node);
        } else if (LIST.isEqual(nodeName)) {
            handleList(node);
        } else if (SET.isEqual(nodeName)) {
            handleSet(node);
        } else if (TOPIC.isEqual(nodeName)) {
            handleTopic(node);
        } else if (RELIABLE_TOPIC.isEqual(nodeName)) {
            handleReliableTopic(node);
        } else if (CACHE.isEqual(nodeName)) {
            handleCache(node);
        } else if (NATIVE_MEMORY.isEqual(nodeName)) {
            fillNativeMemoryConfig(node, config.getNativeMemoryConfig());
        } else if (JOB_TRACKER.isEqual(nodeName)) {
            handleJobTracker(node);
        } else if (SEMAPHORE.isEqual(nodeName)) {
            handleSemaphore(node);
        } else if (RINGBUFFER.isEqual(nodeName)) {
            handleRingbuffer(node);
        } else if (LISTENERS.isEqual(nodeName)) {
            handleListeners(node);
        } else if (PARTITION_GROUP.isEqual(nodeName)) {
            handlePartitionGroup(node);
        } else if (SERIALIZATION.isEqual(nodeName)) {
            handleSerialization(node);
        } else if (SECURITY.isEqual(nodeName)) {
            handleSecurity(node);
        } else if (MEMBER_ATTRIBUTES.isEqual(nodeName)) {
            handleMemberAttributes(node);
        } else if (LICENSE_KEY.isEqual(nodeName)) {
            config.setLicenseKey(getTextContent(node));
        } else if (MANAGEMENT_CENTER.isEqual(nodeName)) {
            handleManagementCenterConfig(node);
        } else if (QUORUM.isEqual(nodeName)) {
            handleQuorum(node);
        } else if (LITE_MEMBER.isEqual(nodeName)) {
            handleLiteMember(node);
        } else if (HOT_RESTART_PERSISTENCE.isEqual(nodeName)) {
            handleHotRestartPersistence(node);
        } else {
            return true;
        }
        return false;
    }

    private void handleInstanceName(Node node) {
        String instanceName = getTextContent(node);
        if (instanceName.isEmpty()) {
            throw new InvalidConfigurationException("Instance name in XML configuration is empty");
        }
        config.setInstanceName(instanceName);
    }

    private void handleHotRestartPersistence(Node hrRoot) {
        HotRestartPersistenceConfig hrConfig = new HotRestartPersistenceConfig();
        Node attrEnabled = hrRoot.getAttributes().getNamedItem("enabled");
        boolean enabled = getBooleanValue(getTextContent(attrEnabled));
        hrConfig.setEnabled(enabled);

        final String parallelismName = "parallelism";
        final String validationTimeoutName = "validation-timeout-seconds";
        final String dataLoadTimeoutName = "data-load-timeout-seconds";

        for (Node n : childElements(hrRoot)) {
            String name = cleanNodeName(n);
            if ("base-dir".equals(name)) {
                hrConfig.setBaseDir(new File(getTextContent(n)).getAbsoluteFile());
            } else if (parallelismName.equals(name)) {
                hrConfig.setParallelism(getIntegerValue(parallelismName, getTextContent(n)));
            } else if (validationTimeoutName.equals(name)) {
                hrConfig.setValidationTimeoutSeconds(getIntegerValue(validationTimeoutName, getTextContent(n)));
            } else if (dataLoadTimeoutName.equals(name)) {
                hrConfig.setDataLoadTimeoutSeconds(getIntegerValue(dataLoadTimeoutName, getTextContent(n)));
            }
        }
        config.setHotRestartPersistenceConfig(hrConfig);
    }

    private void handleLiteMember(Node node) {
        Node attrEnabled = node.getAttributes().getNamedItem("enabled");
        boolean liteMember = attrEnabled != null && getBooleanValue(getTextContent(attrEnabled));
        this.config.setLiteMember(liteMember);
    }

    private void handleQuorum(Node node) {
        QuorumConfig quorumConfig = new QuorumConfig();
        String name = getAttribute(node, "name");
        quorumConfig.setName(name);
        Node attrEnabled = node.getAttributes().getNamedItem("enabled");
        boolean enabled = attrEnabled != null && getBooleanValue(getTextContent(attrEnabled));
        quorumConfig.setEnabled(enabled);
        for (Node n : childElements(node)) {
            String value = getTextContent(n).trim();
            String nodeName = cleanNodeName(n);
            if ("quorum-size".equals(nodeName)) {
                quorumConfig.setSize(getIntegerValue("quorum-size", value));
            } else if ("quorum-listeners".equals(nodeName)) {
                for (Node listenerNode : childElements(n)) {
                    if ("quorum-listener".equals(cleanNodeName(listenerNode))) {
                        String listenerClass = getTextContent(listenerNode);
                        quorumConfig.addListenerConfig(new QuorumListenerConfig(listenerClass));
                    }
                }
            } else if ("quorum-type".equals(nodeName)) {
                quorumConfig.setType(QuorumType.valueOf(upperCaseInternal(value)));
            } else if ("quorum-function-class-name".equals(nodeName)) {
                quorumConfig.setQuorumFunctionClassName(value);
            }
        }
        this.config.addQuorumConfig(quorumConfig);
    }


    private void handleServices(Node node) {
        Node attDefaults = node.getAttributes().getNamedItem("enable-defaults");
        boolean enableDefaults = attDefaults == null || getBooleanValue(getTextContent(attDefaults));
        ServicesConfig servicesConfig = config.getServicesConfig();
        servicesConfig.setEnableDefaults(enableDefaults);

        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("service".equals(nodeName)) {
                ServiceConfig serviceConfig = new ServiceConfig();
                String enabledValue = getAttribute(child, "enabled");
                boolean enabled = getBooleanValue(enabledValue);
                serviceConfig.setEnabled(enabled);

                for (Node n : childElements(child)) {
                    String value = cleanNodeName(n);
                    if ("name".equals(value)) {
                        String name = getTextContent(n);
                        serviceConfig.setName(name);
                    } else if ("class-name".equals(value)) {
                        String className = getTextContent(n);
                        serviceConfig.setClassName(className);
                    } else if ("properties".equals(value)) {
                        fillProperties(n, serviceConfig.getProperties());
                    } else if ("configuration".equals(value)) {
                        Node parserNode = n.getAttributes().getNamedItem("parser");
                        String parserClass = getTextContent(parserNode);
                        if (parserNode == null || parserClass == null) {
                            throw new InvalidConfigurationException("Parser is required!");
                        }
                        try {
                            ServiceConfigurationParser parser = ClassLoaderUtil.newInstance(config.getClassLoader(), parserClass);
                            Object obj = parser.parse((Element) n);
                            serviceConfig.setConfigObject(obj);
                        } catch (Exception e) {
                            ExceptionUtil.sneakyThrow(e);
                        }
                    }
                }
                servicesConfig.addServiceConfig(serviceConfig);
            }
        }
    }

    private void handleWanReplication(Node node) throws Exception {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);

        WanReplicationConfig wanReplicationConfig = new WanReplicationConfig();
        wanReplicationConfig.setName(name);

        for (Node nodeTarget : childElements(node)) {
            String nodeName = cleanNodeName(nodeTarget);
            if ("wan-publisher".equals(nodeName)) {
                WanPublisherConfig publisherConfig = new WanPublisherConfig();
                publisherConfig.setGroupName(getAttribute(nodeTarget, "group-name"));
                for (Node targetChild : childElements(nodeTarget)) {
                    handleWanPublisherConfig(publisherConfig, targetChild);
                }
                wanReplicationConfig.addWanPublisherConfig(publisherConfig);
            } else if ("wan-consumer".equals(nodeName)) {
                WanConsumerConfig consumerConfig = new WanConsumerConfig();
                for (Node targetChild : childElements(nodeTarget)) {
                    handleWanConsumerConfig(consumerConfig, targetChild);
                }
                wanReplicationConfig.setWanConsumerConfig(consumerConfig);
            }

        }
        config.addWanReplicationConfig(wanReplicationConfig);
    }

    private void handleWanPublisherConfig(WanPublisherConfig publisherConfig, Node targetChild) {
        String targetChildName = cleanNodeName(targetChild);
        if ("class-name".equals(targetChildName)) {
            publisherConfig.setClassName(getTextContent(targetChild));
        } else if ("queue-full-behavior".equals(targetChildName)) {
            String queueFullBehavior = getTextContent(targetChild);
            publisherConfig.setQueueFullBehavior(WANQueueFullBehavior.valueOf(upperCaseInternal(queueFullBehavior)));
        } else if ("queue-capacity".equals(targetChildName)) {
            int queueCapacity = getIntegerValue("queue-capacity", getTextContent(targetChild));
            publisherConfig.setQueueCapacity(queueCapacity);
        } else if ("properties".equals(targetChildName)) {
            fillProperties(targetChild, publisherConfig.getProperties());
        }
    }

    private void handleWanConsumerConfig(WanConsumerConfig consumerConfig, Node targetChild) {
        String targetChildName = cleanNodeName(targetChild);
        if ("class-name".equals(targetChildName)) {
            consumerConfig.setClassName(getTextContent(targetChild));
        } else if ("properties".equals(targetChildName)) {
            fillProperties(targetChild, consumerConfig.getProperties());
        }
    }

    private void handleNetwork(Node node) throws Exception {
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("reuse-address".equals(nodeName)) {
                String value = getTextContent(child).trim();
                config.getNetworkConfig().setReuseAddress(getBooleanValue(value));
            } else if ("port".equals(nodeName)) {
                handlePort(child);
            } else if ("outbound-ports".equals(nodeName)) {
                handleOutboundPorts(child);
            } else if ("public-address".equals(nodeName)) {
                String address = getTextContent(child);
                config.getNetworkConfig().setPublicAddress(address);
            } else if ("join".equals(nodeName)) {
                handleJoin(child);
            } else if ("interfaces".equals(nodeName)) {
                handleInterfaces(child);
            } else if ("symmetric-encryption".equals(nodeName)) {
                handleViaReflection(child, config.getNetworkConfig(), new SymmetricEncryptionConfig());
            } else if ("ssl".equals(nodeName)) {
                handleSSLConfig(child);
            } else if ("socket-interceptor".equals(nodeName)) {
                handleSocketInterceptorConfig(child);
            }
        }
    }

    private void handleExecutor(Node node) throws Exception {
        ExecutorConfig executorConfig = new ExecutorConfig();
        handleViaReflection(node, config, executorConfig);
    }

    private void handleGroup(Node node) {
        for (Node n : childElements(node)) {
            String value = getTextContent(n).trim();
            String nodeName = cleanNodeName(n);
            if ("name".equals(nodeName)) {
                config.getGroupConfig().setName(value);
            } else if ("password".equals(nodeName)) {
                config.getGroupConfig().setPassword(value);
            }
        }
    }

    private void handleInterfaces(Node node) {
        NamedNodeMap atts = node.getAttributes();
        InterfacesConfig interfaces = config.getNetworkConfig().getInterfaces();
        for (int a = 0; a < atts.getLength(); a++) {
            Node att = atts.item(a);
            if ("enabled".equals(att.getNodeName())) {
                String value = att.getNodeValue();
                interfaces.setEnabled(getBooleanValue(value));
            }
        }
        for (Node n : childElements(node)) {
            if ("interface".equals(lowerCaseInternal(cleanNodeName(n)))) {
                String value = getTextContent(n).trim();
                interfaces.addInterface(value);
            }
        }
    }

    private void handleViaReflection(Node node, Object parent, Object child) throws Exception {
        NamedNodeMap atts = node.getAttributes();
        if (atts != null) {
            for (int a = 0; a < atts.getLength(); a++) {
                Node att = atts.item(a);
                invokeSetter(child, att, att.getNodeValue());
            }
        }
        for (Node n : childElements(node)) {
            if (n instanceof Element) {
                invokeSetter(child, n, getTextContent(n).trim());
            }
        }
        attachChildConfig(parent, child);
    }

    private static void invokeSetter(Object target, Node node, String argument) {
        Method method = getMethod(target, "set" + toPropertyName(cleanNodeName(node)), true);
        if (method == null) {
            throw new InvalidConfigurationException("Invalid element/attribute name in XML configuration: " + node);
        }
        Class<?> arg = method.getParameterTypes()[0];
        Object coercedArg =
                arg == String.class ? argument
                        : arg == int.class ? Integer.valueOf(argument)
                        : arg == long.class ? Long.valueOf(argument)
                        : arg == boolean.class ? getBooleanValue(argument)
                        : null;
        if (coercedArg == null) {
            throw new HazelcastException(String.format(
                    "Method %s has unsupported argument type %s", method.getName(), arg.getSimpleName()));
        }
        try {
            method.invoke(target, coercedArg);
        } catch (Exception e) {
            throw new HazelcastException(e);
        }
    }

    private static void attachChildConfig(Object parent, Object child) throws Exception {
        String targetName = child.getClass().getSimpleName();
        Method attacher = getMethod(parent, "set" + targetName, false);
        if (attacher == null) {
            attacher = getMethod(parent, "add" + targetName, false);
        }
        if (attacher == null) {
            throw new HazelcastException(String.format(
                    "%s doesn't accept %s as child", parent.getClass().getSimpleName(), targetName));
        }
        attacher.invoke(parent, child);
    }

    private static Method getMethod(Object target, String methodName, boolean requiresArg) {
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (method.getName().equalsIgnoreCase(methodName)) {
                if (!requiresArg) {
                    return method;
                }
                Class<?>[] args = method.getParameterTypes();
                if (args.length != 1) {
                    continue;
                }
                Class<?> arg = method.getParameterTypes()[0];
                if (arg == String.class || arg == int.class || arg == long.class || arg == boolean.class) {
                    return method;
                }
            }
        }
        return null;
    }

    private static String toPropertyName(String element) {
        StringBuilder sb = new StringBuilder();
        char[] chars = element.toCharArray();
        boolean upper = true;
        for (char c : chars) {
            if (c == '_' || c == '-' || c == '.') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void handleJoin(Node node) {
        for (Node child : childElements(node)) {
            String name = cleanNodeName(child);
            if ("multicast".equals(name)) {
                handleMulticast(child);
            } else if ("tcp-ip".equals(name)) {
                handleTcpIp(child);
            } else if ("aws".equals(name)) {
                handleAWS(child);
            } else if ("discovery-strategies".equals(name)) {
                handleDiscoveryStrategies(child);
            }
        }

        JoinConfig joinConfig = config.getNetworkConfig().getJoin();
        joinConfig.verify();
    }

    private void handleDiscoveryStrategies(Node node) {
        JoinConfig join = config.getNetworkConfig().getJoin();
        DiscoveryConfig discoveryConfig = join.getDiscoveryConfig();
        for (Node child : childElements(node)) {
            String name = cleanNodeName(child);
            if ("discovery-strategy".equals(name)) {
                handleDiscoveryStrategy(child, discoveryConfig);
            } else if ("node-filter".equals(name)) {
                handleDiscoveryNodeFilter(child, discoveryConfig);
            }
        }
    }

    private void handleDiscoveryNodeFilter(Node node, DiscoveryConfig discoveryConfig) {
        NamedNodeMap atts = node.getAttributes();

        Node att = atts.getNamedItem("class");
        if (att != null) {
            discoveryConfig.setNodeFilterClass(getTextContent(att).trim());
        }
    }

    private void handleDiscoveryStrategy(Node node, DiscoveryConfig discoveryConfig) {
        NamedNodeMap atts = node.getAttributes();

        boolean enabled = false;
        String clazz = null;

        for (int a = 0; a < atts.getLength(); a++) {
            Node att = atts.item(a);
            String value = getTextContent(att).trim();
            if ("enabled".equals(lowerCaseInternal(att.getNodeName()))) {
                enabled = getBooleanValue(value);
            } else if ("class".equals(att.getNodeName())) {
                clazz = value;
            }
        }

        if (!enabled || clazz == null) {
            return;
        }

        Map<String, Comparable> properties = new HashMap<String, Comparable>();
        for (Node child : childElements(node)) {
            String name = cleanNodeName(child);
            if ("properties".equals(name)) {
                fillProperties(child, properties);
            }
        }

        discoveryConfig.addDiscoveryStrategyConfig(new DiscoveryStrategyConfig(clazz, properties));
    }

    private void handleAWS(Node node) {
        JoinConfig join = config.getNetworkConfig().getJoin();
        NamedNodeMap atts = node.getAttributes();
        AwsConfig awsConfig = join.getAwsConfig();
        for (int a = 0; a < atts.getLength(); a++) {
            Node att = atts.item(a);
            String value = getTextContent(att).trim();
            if ("enabled".equals(lowerCaseInternal(att.getNodeName()))) {
                awsConfig.setEnabled(getBooleanValue(value));
            } else if (att.getNodeName().equals("connection-timeout-seconds")) {
                awsConfig.setConnectionTimeoutSeconds(getIntegerValue("connection-timeout-seconds", value));
            }
        }
        for (Node n : childElements(node)) {
            String value = getTextContent(n).trim();
            if ("secret-key".equals(cleanNodeName(n))) {
                awsConfig.setSecretKey(value);
            } else if ("access-key".equals(cleanNodeName(n))) {
                awsConfig.setAccessKey(value);
            } else if ("region".equals(cleanNodeName(n))) {
                awsConfig.setRegion(value);
            } else if ("host-header".equals(cleanNodeName(n))) {
                awsConfig.setHostHeader(value);
            } else if ("security-group-name".equals(cleanNodeName(n))) {
                awsConfig.setSecurityGroupName(value);
            } else if ("tag-key".equals(cleanNodeName(n))) {
                awsConfig.setTagKey(value);
            } else if ("tag-value".equals(cleanNodeName(n))) {
                awsConfig.setTagValue(value);
            } else if ("iam-role".equals(cleanNodeName(n))) {
                awsConfig.setIamRole(value);
            }
        }
    }

    private void handleMulticast(Node node) {
        JoinConfig join = config.getNetworkConfig().getJoin();
        NamedNodeMap atts = node.getAttributes();
        MulticastConfig multicastConfig = join.getMulticastConfig();
        for (int a = 0; a < atts.getLength(); a++) {
            Node att = atts.item(a);
            String value = getTextContent(att).trim();
            if ("enabled".equals(lowerCaseInternal(att.getNodeName()))) {
                multicastConfig.setEnabled(getBooleanValue(value));
            } else if ("loopbackmodeenabled".equals(lowerCaseInternal(att.getNodeName()))) {
                multicastConfig.setLoopbackModeEnabled(getBooleanValue(value));
            }
        }
        for (Node n : childElements(node)) {
            String value = getTextContent(n).trim();
            if ("multicast-group".equals(cleanNodeName(n))) {
                multicastConfig.setMulticastGroup(value);
            } else if ("multicast-port".equals(cleanNodeName(n))) {
                multicastConfig.setMulticastPort(parseInt(value));
            } else if ("multicast-timeout-seconds".equals(cleanNodeName(n))) {
                multicastConfig.setMulticastTimeoutSeconds(parseInt(value));
            } else if ("multicast-time-to-live-seconds".equals(cleanNodeName(n))) {
                //we need this line for the time being to prevent not reading the multicast-time-to-live-seconds property
                //for more info see: https://github.com/hazelcast/hazelcast/issues/752
                multicastConfig.setMulticastTimeToLive(parseInt(value));
            } else if ("multicast-time-to-live".equals(cleanNodeName(n))) {
                multicastConfig.setMulticastTimeToLive(parseInt(value));
            } else if ("trusted-interfaces".equals(cleanNodeName(n))) {
                for (Node child : childElements(n)) {
                    if ("interface".equals(lowerCaseInternal(cleanNodeName(child)))) {
                        multicastConfig.addTrustedInterface(getTextContent(child).trim());
                    }
                }
            }
        }
    }

    private void handleTcpIp(Node node) {
        NamedNodeMap atts = node.getAttributes();
        JoinConfig join = config.getNetworkConfig().getJoin();
        TcpIpConfig tcpIpConfig = join.getTcpIpConfig();
        for (int a = 0; a < atts.getLength(); a++) {
            Node att = atts.item(a);
            String value = getTextContent(att).trim();
            if (att.getNodeName().equals("enabled")) {
                tcpIpConfig.setEnabled(getBooleanValue(value));
            } else if (att.getNodeName().equals("connection-timeout-seconds")) {
                tcpIpConfig.setConnectionTimeoutSeconds(getIntegerValue("connection-timeout-seconds", value));
            }
        }
        Set<String> memberTags = new HashSet<String>(Arrays.asList("interface", "member", "members"));
        for (Node n : childElements(node)) {
            String value = getTextContent(n).trim();
            if (cleanNodeName(n).equals("member-list")) {
                handleMemberList(n);
            } else if (cleanNodeName(n).equals("required-member")) {
                if (tcpIpConfig.getRequiredMember() != null) {
                    throw new InvalidConfigurationException("Duplicate required-member"
                            + " definition found in XML configuration. ");
                }
                tcpIpConfig.setRequiredMember(value);
            } else if (memberTags.contains(cleanNodeName(n))) {
                tcpIpConfig.addMember(value);
            }
        }
    }

    private void handleMemberList(Node node) {
        JoinConfig join = config.getNetworkConfig().getJoin();
        TcpIpConfig tcpIpConfig = join.getTcpIpConfig();
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            if ("member".equals(nodeName)) {
                String value = getTextContent(n).trim();
                tcpIpConfig.addMember(value);
            }
        }
    }

    private void handlePort(Node node) {
        String portStr = getTextContent(node).trim();
        NetworkConfig networkConfig = config.getNetworkConfig();
        if (portStr != null && portStr.length() > 0) {
            networkConfig.setPort(parseInt(portStr));
        }
        NamedNodeMap atts = node.getAttributes();
        for (int a = 0; a < atts.getLength(); a++) {
            Node att = atts.item(a);
            String value = getTextContent(att).trim();

            if ("auto-increment".equals(att.getNodeName())) {
                networkConfig.setPortAutoIncrement(getBooleanValue(value));
            } else if ("port-count".equals(att.getNodeName())) {
                int portCount = parseInt(value);
                networkConfig.setPortCount(portCount);
            }
        }
    }

    private void handleOutboundPorts(Node child) {
        NetworkConfig networkConfig = config.getNetworkConfig();
        for (Node n : childElements(child)) {
            String nodeName = cleanNodeName(n);
            if ("ports".equals(nodeName)) {
                String value = getTextContent(n);
                networkConfig.addOutboundPortDefinition(value);
            }
        }
    }

    private void handleQueue(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        QueueConfig qConfig = new QueueConfig();
        qConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("max-size".equals(nodeName)) {
                qConfig.setMaxSize(getIntegerValue("max-size", value));
            } else if ("backup-count".equals(nodeName)) {
                qConfig.setBackupCount(getIntegerValue("backup-count", value));
            } else if ("async-backup-count".equals(nodeName)) {
                qConfig.setAsyncBackupCount(getIntegerValue("async-backup-count", value));
            } else if ("item-listeners".equals(nodeName)) {
                for (Node listenerNode : childElements(n)) {
                    if ("item-listener".equals(cleanNodeName(listenerNode))) {
                        NamedNodeMap attrs = listenerNode.getAttributes();
                        boolean incValue = getBooleanValue(getTextContent(attrs.getNamedItem("include-value")));
                        String listenerClass = getTextContent(listenerNode);
                        qConfig.addItemListenerConfig(new ItemListenerConfig(listenerClass, incValue));
                    }
                }
            } else if ("statistics-enabled".equals(nodeName)) {
                qConfig.setStatisticsEnabled(getBooleanValue(value));
            } else if ("queue-store".equals(nodeName)) {
                QueueStoreConfig queueStoreConfig = createQueueStoreConfig(n);
                qConfig.setQueueStoreConfig(queueStoreConfig);
            } else if ("empty-queue-ttl".equals(nodeName)) {
                qConfig.setEmptyQueueTtl(getIntegerValue("empty-queue-ttl", value));
            }
        }
        this.config.addQueueConfig(qConfig);
    }

    private void handleList(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        ListConfig lConfig = new ListConfig();
        lConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("max-size".equals(nodeName)) {
                lConfig.setMaxSize(getIntegerValue("max-size", value));
            } else if ("backup-count".equals(nodeName)) {
                lConfig.setBackupCount(getIntegerValue("backup-count", value));
            } else if ("async-backup-count".equals(nodeName)) {
                lConfig.setAsyncBackupCount(getIntegerValue("async-backup-count", value));
            } else if ("item-listeners".equals(nodeName)) {
                for (Node listenerNode : childElements(n)) {
                    if ("item-listener".equals(cleanNodeName(listenerNode))) {
                        NamedNodeMap attrs = listenerNode.getAttributes();
                        boolean incValue = getBooleanValue(getTextContent(attrs.getNamedItem("include-value")));
                        String listenerClass = getTextContent(listenerNode);
                        lConfig.addItemListenerConfig(new ItemListenerConfig(listenerClass, incValue));
                    }
                }
            } else if ("statistics-enabled".equals(nodeName)) {
                lConfig.setStatisticsEnabled(getBooleanValue(value));
            }
        }
        this.config.addListConfig(lConfig);
    }

    private void handleSet(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        SetConfig sConfig = new SetConfig();
        sConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("max-size".equals(nodeName)) {
                sConfig.setMaxSize(getIntegerValue("max-size", value));
            } else if ("backup-count".equals(nodeName)) {
                sConfig.setBackupCount(getIntegerValue("backup-count", value));
            } else if ("async-backup-count".equals(nodeName)) {
                sConfig.setAsyncBackupCount(getIntegerValue("async-backup-count", value));
            } else if ("item-listeners".equals(nodeName)) {
                for (Node listenerNode : childElements(n)) {
                    if ("item-listener".equals(cleanNodeName(listenerNode))) {
                        NamedNodeMap attrs = listenerNode.getAttributes();
                        boolean incValue = getBooleanValue(getTextContent(attrs.getNamedItem("include-value")));
                        String listenerClass = getTextContent(listenerNode);
                        sConfig.addItemListenerConfig(new ItemListenerConfig(listenerClass, incValue));
                    }
                }
            } else if ("statistics-enabled".equals(nodeName)) {
                sConfig.setStatisticsEnabled(getBooleanValue(value));
            }
        }
        this.config.addSetConfig(sConfig);
    }

    private void handleMultiMap(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        MultiMapConfig multiMapConfig = new MultiMapConfig();
        multiMapConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("value-collection-type".equals(nodeName)) {
                multiMapConfig.setValueCollectionType(value);
            } else if ("backup-count".equals(nodeName)) {
                multiMapConfig.setBackupCount(getIntegerValue("backup-count"
                        , value));
            } else if ("async-backup-count".equals(nodeName)) {
                multiMapConfig.setAsyncBackupCount(getIntegerValue("async-backup-count"
                        , value));
            } else if ("entry-listeners".equals(nodeName)) {
                for (Node listenerNode : childElements(n)) {
                    if ("entry-listener".equals(cleanNodeName(listenerNode))) {
                        NamedNodeMap attrs = listenerNode.getAttributes();
                        boolean incValue = getBooleanValue(getTextContent(attrs.getNamedItem("include-value")));
                        boolean local = getBooleanValue(getTextContent(attrs.getNamedItem("local")));
                        String listenerClass = getTextContent(listenerNode);
                        multiMapConfig.addEntryListenerConfig(new EntryListenerConfig(listenerClass, local, incValue));
                    }
                }
            } else if ("statistics-enabled".equals(nodeName)) {
                multiMapConfig.setStatisticsEnabled(getBooleanValue(value));
            } else if ("binary".equals(nodeName)) {
                multiMapConfig.setBinary(getBooleanValue(value));
            }
        }
        this.config.addMultiMapConfig(multiMapConfig);
    }

    private void handleReplicatedMap(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        ReplicatedMapConfig replicatedMapConfig = new ReplicatedMapConfig();
        replicatedMapConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("concurrency-level".equals(nodeName)) {
                replicatedMapConfig.setConcurrencyLevel(getIntegerValue("concurrency-level"
                        , value));
            } else if ("in-memory-format".equals(nodeName)) {
                replicatedMapConfig.setInMemoryFormat(InMemoryFormat.valueOf(upperCaseInternal(value)));
            } else if ("replication-delay-millis".equals(nodeName)) {
                replicatedMapConfig.setReplicationDelayMillis(getIntegerValue("replication-delay-millis"
                        , value));
            } else if ("async-fillup".equals(nodeName)) {
                replicatedMapConfig.setAsyncFillup(getBooleanValue(value));
            } else if ("statistics-enabled".equals(nodeName)) {
                replicatedMapConfig.setStatisticsEnabled(getBooleanValue(value));
            } else if ("entry-listeners".equals(nodeName)) {
                for (Node listenerNode : childElements(n)) {
                    if ("entry-listener".equals(cleanNodeName(listenerNode))) {
                        NamedNodeMap attrs = listenerNode.getAttributes();
                        boolean incValue = getBooleanValue(getTextContent(attrs.getNamedItem("include-value")));
                        boolean local = getBooleanValue(getTextContent(attrs.getNamedItem("local")));
                        String listenerClass = getTextContent(listenerNode);
                        replicatedMapConfig.addEntryListenerConfig(new EntryListenerConfig(listenerClass, local, incValue));
                    }
                }
            } else if ("merge-policy".equals(nodeName)) {
                replicatedMapConfig.setMergePolicy(value);
            }
        }
        this.config.addReplicatedMapConfig(replicatedMapConfig);
    }

    //CHECKSTYLE:OFF
    private void handleMap(Node parentNode) throws Exception {
        String name = getAttribute(parentNode, "name");
        MapConfig mapConfig = new MapConfig();
        mapConfig.setName(name);
        for (Node node : childElements(parentNode)) {
            String nodeName = cleanNodeName(node);
            String value = getTextContent(node).trim();
            if ("backup-count".equals(nodeName)) {
                mapConfig.setBackupCount(getIntegerValue("backup-count", value));
            } else if ("in-memory-format".equals(nodeName)) {
                mapConfig.setInMemoryFormat(InMemoryFormat.valueOf(upperCaseInternal(value)));
            } else if ("async-backup-count".equals(nodeName)) {
                mapConfig.setAsyncBackupCount(getIntegerValue("async-backup-count", value));
            } else if ("eviction-policy".equals(nodeName)) {
                if (mapConfig.getMapEvictionPolicy() == null) {
                    mapConfig.setEvictionPolicy(EvictionPolicy.valueOf(upperCaseInternal(value)));
                }
            } else if ("max-size".equals(nodeName)) {
                MaxSizeConfig msc = mapConfig.getMaxSizeConfig();
                Node maxSizePolicy = node.getAttributes().getNamedItem("policy");
                if (maxSizePolicy != null) {
                    msc.setMaxSizePolicy(MaxSizeConfig.MaxSizePolicy.valueOf(
                            upperCaseInternal(getTextContent(maxSizePolicy))));
                }
                int size = sizeParser(value);
                msc.setSize(size);
            } else if ("eviction-percentage".equals(nodeName)) {
                mapConfig.setEvictionPercentage(getIntegerValue("eviction-percentage", value
                ));
            } else if ("min-eviction-check-millis".equals(nodeName)) {
                mapConfig.setMinEvictionCheckMillis(getLongValue("min-eviction-check-millis", value
                ));
            } else if ("time-to-live-seconds".equals(nodeName)) {
                mapConfig.setTimeToLiveSeconds(getIntegerValue("time-to-live-seconds", value
                ));
            } else if ("max-idle-seconds".equals(nodeName)) {
                mapConfig.setMaxIdleSeconds(getIntegerValue("max-idle-seconds", value
                ));
            } else if ("map-store".equals(nodeName)) {
                MapStoreConfig mapStoreConfig = createMapStoreConfig(node);
                mapConfig.setMapStoreConfig(mapStoreConfig);
            } else if ("near-cache".equals(nodeName)) {
                mapConfig.setNearCacheConfig(handleNearCacheConfig(node));
            } else if ("merge-policy".equals(nodeName)) {
                mapConfig.setMergePolicy(value);
            } else if ("hot-restart".equals(nodeName)) {
                mapConfig.setHotRestartConfig(createHotRestartConfig(node));
            } else if ("read-backup-data".equals(nodeName)) {
                mapConfig.setReadBackupData(getBooleanValue(value));
            } else if ("statistics-enabled".equals(nodeName)) {
                mapConfig.setStatisticsEnabled(getBooleanValue(value));
            } else if ("optimize-queries".equals(nodeName)) {
                mapConfig.setOptimizeQueries(getBooleanValue(value));
            } else if ("cache-deserialized-values".equals(nodeName)) {
                CacheDeserializedValues cacheDeserializedValues = CacheDeserializedValues.parseString(value);
                mapConfig.setCacheDeserializedValues(cacheDeserializedValues);
            } else if ("wan-replication-ref".equals(nodeName)) {
                mapWanReplicationRefHandle(node, mapConfig);
            } else if ("indexes".equals(nodeName)) {
                mapIndexesHandle(node, mapConfig);
            } else if ("attributes".equals(nodeName)) {
                mapAttributesHandle(node, mapConfig);
            } else if ("entry-listeners".equals(nodeName)) {
                mapEntryListenerHandle(node, mapConfig);
            } else if ("partition-lost-listeners".equals(nodeName)) {
                mapPartitionLostListenerHandle(node, mapConfig);
            } else if ("partition-strategy".equals(nodeName)) {
                mapConfig.setPartitioningStrategyConfig(new PartitioningStrategyConfig(value));
            } else if ("quorum-ref".equals(nodeName)) {
                mapConfig.setQuorumName(value);
            } else if ("query-caches".equals(nodeName)) {
                mapQueryCacheHandler(node, mapConfig);
            } else if ("map-eviction-policy-class-name".equals(nodeName)) {
                String className = checkHasText(getTextContent(node), "map-eviction-policy-class-name cannot be null or empty");
                try {
                    MapEvictionPolicy mapEvictionPolicy = ClassLoaderUtil.newInstance(config.getClassLoader(), className);
                    mapConfig.setMapEvictionPolicy(mapEvictionPolicy);
                } catch (Exception e) {
                    throw ExceptionUtil.rethrow(e);
                }
            }
        }
        this.config.addMapConfig(mapConfig);
    }
    //CHECKSTYLE:ON


    private NearCacheConfig handleNearCacheConfig(Node node) {
        String name = getAttribute(node, "name");
        NearCacheConfig nearCacheConfig = new NearCacheConfig(name);
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            String value = getTextContent(child).trim();
            if ("max-size".equals(nodeName)) {
                nearCacheConfig.setMaxSize(Integer.parseInt(value));
                LOGGER.warning("The element <max-size/> for <near-cache/> is deprecated, please use <eviction/> instead!");
            } else if ("time-to-live-seconds".equals(nodeName)) {
                nearCacheConfig.setTimeToLiveSeconds(Integer.parseInt(value));
            } else if ("max-idle-seconds".equals(nodeName)) {
                nearCacheConfig.setMaxIdleSeconds(Integer.parseInt(value));
            } else if ("eviction-policy".equals(nodeName)) {
                nearCacheConfig.setEvictionPolicy(value);
                LOGGER.warning("The element <eviction-policy/> for <near-cache/> is deprecated, please use <eviction/> instead!");
            } else if ("in-memory-format".equals(nodeName)) {
                nearCacheConfig.setInMemoryFormat(InMemoryFormat.valueOf(upperCaseInternal(value)));
            } else if ("invalidate-on-change".equals(nodeName)) {
                nearCacheConfig.setInvalidateOnChange(Boolean.parseBoolean(value));
            } else if ("cache-local-entries".equals(nodeName)) {
                nearCacheConfig.setCacheLocalEntries(Boolean.parseBoolean(value));
            } else if ("local-update-policy".equals(nodeName)) {
                NearCacheConfig.LocalUpdatePolicy policy = NearCacheConfig.LocalUpdatePolicy.valueOf(value);
                nearCacheConfig.setLocalUpdatePolicy(policy);
            } else if ("eviction".equals(nodeName)) {
                nearCacheConfig.setEvictionConfig(getEvictionConfig(child));
            }
        }
        return nearCacheConfig;
    }

    private HotRestartConfig createHotRestartConfig(Node node) {
        HotRestartConfig hotRestartConfig = new HotRestartConfig();

        Node attrEnabled = node.getAttributes().getNamedItem("enabled");
        boolean enabled = getBooleanValue(getTextContent(attrEnabled));
        hotRestartConfig.setEnabled(enabled);

        for (Node n : childElements(node)) {
            String name = cleanNodeName(n);
            if ("fsync".equals(name)) {
                hotRestartConfig.setFsync(getBooleanValue(getTextContent(n)));
            }
        }

        return hotRestartConfig;
    }

    private void handleCache(Node node) throws Exception {
        String name = getAttribute(node, "name");
        CacheSimpleConfig cacheConfig = new CacheSimpleConfig();
        cacheConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("key-type".equals(nodeName)) {
                cacheConfig.setKeyType(getAttribute(n, "class-name"));
            } else if ("value-type".equals(nodeName)) {
                cacheConfig.setValueType(getAttribute(n, "class-name"));
            } else if ("statistics-enabled".equals(nodeName)) {
                cacheConfig.setStatisticsEnabled(getBooleanValue(value));
            } else if ("management-enabled".equals(nodeName)) {
                cacheConfig.setManagementEnabled(getBooleanValue(value));
            } else if ("read-through".equals(nodeName)) {
                cacheConfig.setReadThrough(getBooleanValue(value));
            } else if ("write-through".equals(nodeName)) {
                cacheConfig.setWriteThrough(getBooleanValue(value));
            } else if ("cache-loader-factory".equals(nodeName)) {
                cacheConfig.setCacheLoaderFactory(getAttribute(n, "class-name"));
            } else if ("cache-writer-factory".equals(nodeName)) {
                cacheConfig.setCacheWriterFactory(getAttribute(n, "class-name"));
            } else if ("expiry-policy-factory".equals(nodeName)) {
                cacheConfig.setExpiryPolicyFactoryConfig(getExpiryPolicyFactoryConfig(n));
            } else if ("cache-entry-listeners".equals(nodeName)) {
                cacheListenerHandle(n, cacheConfig);
            } else if ("in-memory-format".equals(nodeName)) {
                cacheConfig.setInMemoryFormat(InMemoryFormat.valueOf(upperCaseInternal(value)));
            } else if ("backup-count".equals(nodeName)) {
                cacheConfig.setBackupCount(getIntegerValue("backup-count", value));
            } else if ("async-backup-count".equals(nodeName)) {
                cacheConfig.setAsyncBackupCount(getIntegerValue("async-backup-count", value));
            } else if ("wan-replication-ref".equals(nodeName)) {
                cacheWanReplicationRefHandle(n, cacheConfig);
            } else if ("eviction".equals(nodeName)) {
                EvictionConfig evictionConfig = getEvictionConfig(n);
                try {
                    EvictionConfigHelper.checkEvictionConfig(evictionConfig);
                } catch (IllegalArgumentException e) {
                    throw new InvalidConfigurationException(e.getMessage());
                }
                cacheConfig.setEvictionConfig(evictionConfig);
            } else if ("quorum-ref".equals(nodeName)) {
                cacheConfig.setQuorumName(value);
            } else if ("partition-lost-listeners".equals(nodeName)) {
                cachePartitionLostListenerHandle(n, cacheConfig);
            } else if ("merge-policy".equals(nodeName)) {
                cacheConfig.setMergePolicy(value);
            } else if ("hot-restart".equals(nodeName)) {
                cacheConfig.setHotRestartConfig(createHotRestartConfig(n));
            } else if ("disable-per-entry-invalidation-events".equals(nodeName)) {
                cacheConfig.setDisablePerEntryInvalidationEvents(getBooleanValue(value));
            }
        }
        this.config.addCacheConfig(cacheConfig);
    }

    private ExpiryPolicyFactoryConfig getExpiryPolicyFactoryConfig(Node node) {
        String className = getAttribute(node, "class-name");
        if (!isNullOrEmpty(className)) {
            return new ExpiryPolicyFactoryConfig(className);
        } else {
            TimedExpiryPolicyFactoryConfig timedExpiryPolicyFactoryConfig = null;
            for (Node n : childElements(node)) {
                String nodeName = cleanNodeName(n);
                if ("timed-expiry-policy-factory".equals(nodeName)) {
                    timedExpiryPolicyFactoryConfig = getTimedExpiryPolicyFactoryConfig(n);
                }
            }
            if (timedExpiryPolicyFactoryConfig == null) {
                throw new InvalidConfigurationException(
                        "One of the \"class-name\" or \"timed-expire-policy-factory\" configuration "
                                + "is needed for expiry policy factory configuration");
            } else {
                return new ExpiryPolicyFactoryConfig(timedExpiryPolicyFactoryConfig);
            }
        }
    }

    private TimedExpiryPolicyFactoryConfig getTimedExpiryPolicyFactoryConfig(Node node) {
        String expiryPolicyTypeStr = getAttribute(node, "expiry-policy-type");
        String durationAmountStr = getAttribute(node, "duration-amount");
        String timeUnitStr = getAttribute(node, "time-unit");
        ExpiryPolicyType expiryPolicyType = ExpiryPolicyType.valueOf(upperCaseInternal(expiryPolicyTypeStr));
        if (expiryPolicyType != ExpiryPolicyType.ETERNAL && (isNullOrEmpty(durationAmountStr) || isNullOrEmpty(timeUnitStr))) {
            throw new InvalidConfigurationException(
                    "Both of the \"duration-amount\" or \"time-unit\" attributes "
                            + "are required for expiry policy factory configuration "
                            + "(except \"ETERNAL\" expiry policy type)");
        }
        DurationConfig durationConfig = null;
        if (expiryPolicyType != ExpiryPolicyType.ETERNAL) {
            long durationAmount;
            try {
                durationAmount = parseLong(durationAmountStr);
            } catch (NumberFormatException e) {
                throw new InvalidConfigurationException(
                        "Invalid value for duration amount: " + durationAmountStr, e);
            }
            if (durationAmount <= 0) {
                throw new InvalidConfigurationException(
                        "Duration amount must be positive: " + durationAmount);
            }
            TimeUnit timeUnit;
            try {
                timeUnit = TimeUnit.valueOf(upperCaseInternal(timeUnitStr));
            } catch (IllegalArgumentException e) {
                throw new InvalidConfigurationException(
                        "Invalid value for time unit: " + timeUnitStr, e);
            }
            durationConfig = new DurationConfig(durationAmount, timeUnit);
        }
        return new TimedExpiryPolicyFactoryConfig(expiryPolicyType, durationConfig);
    }

    private EvictionConfig getEvictionConfig(Node node) {
        EvictionConfig evictionConfig = new EvictionConfig();
        Node size = node.getAttributes().getNamedItem("size");
        Node maxSizePolicy = node.getAttributes().getNamedItem("max-size-policy");
        Node evictionPolicy = node.getAttributes().getNamedItem("eviction-policy");
        Node comparatorClassName = node.getAttributes().getNamedItem("comparator-class-name");
        if (size != null) {
            evictionConfig.setSize(parseInt(getTextContent(size)));
        }
        if (maxSizePolicy != null) {
            evictionConfig.setMaximumSizePolicy(MaxSizePolicy.valueOf(upperCaseInternal(getTextContent(maxSizePolicy)))
            );
        }
        if (evictionPolicy != null) {
            evictionConfig.setEvictionPolicy(EvictionPolicy.valueOf(upperCaseInternal(getTextContent(evictionPolicy)))
            );
        }
        if (comparatorClassName != null) {
            evictionConfig.setComparatorClassName(getTextContent(comparatorClassName));
        }
        return evictionConfig;
    }

    private void cacheWanReplicationRefHandle(Node n, CacheSimpleConfig cacheConfig) {
        WanReplicationRef wanReplicationRef = new WanReplicationRef();
        String wanName = getAttribute(n, "name");
        wanReplicationRef.setName(wanName);
        for (Node wanChild : childElements(n)) {
            String wanChildName = cleanNodeName(wanChild);
            String wanChildValue = getTextContent(wanChild);
            if ("merge-policy".equals(wanChildName)) {
                wanReplicationRef.setMergePolicy(wanChildValue);
            } else if ("filters".equals(wanChildName)) {
                handleWanFilters(wanChild, wanReplicationRef);
            } else if ("republishing-enabled".equals(wanChildName)) {
                wanReplicationRef.setRepublishingEnabled(getBooleanValue(wanChildValue));
            }
        }
        cacheConfig.setWanReplicationRef(wanReplicationRef);
    }

    private void handleWanFilters(Node wanChild, WanReplicationRef wanReplicationRef) {
        for (Node filter : childElements(wanChild)) {
            if ("filter-impl".equals(cleanNodeName(filter))) {
                wanReplicationRef.addFilter(getTextContent(filter));
            }
        }
    }

    private void cachePartitionLostListenerHandle(Node n, CacheSimpleConfig cacheConfig) {
        for (Node listenerNode : childElements(n)) {
            if ("partition-lost-listener".equals(cleanNodeName(listenerNode))) {
                String listenerClass = getTextContent(listenerNode);
                cacheConfig.addCachePartitionLostListenerConfig(
                        new CachePartitionLostListenerConfig(listenerClass));
            }
        }
    }

    private void cacheListenerHandle(Node n, CacheSimpleConfig cacheSimpleConfig) {
        for (Node listenerNode : childElements(n)) {
            if ("cache-entry-listener".equals(cleanNodeName(listenerNode))) {
                CacheSimpleEntryListenerConfig listenerConfig = new CacheSimpleEntryListenerConfig();
                for (Node listenerChildNode : childElements(listenerNode)) {
                    if ("cache-entry-listener-factory".equals(cleanNodeName(listenerChildNode))) {
                        listenerConfig.setCacheEntryListenerFactory(getAttribute(listenerChildNode, "class-name"));
                    }
                    if ("cache-entry-event-filter-factory".equals(cleanNodeName(listenerChildNode))) {
                        listenerConfig.setCacheEntryEventFilterFactory(getAttribute(listenerChildNode, "class-name"));
                    }
                }
                NamedNodeMap attrs = listenerNode.getAttributes();
                listenerConfig.setOldValueRequired(getBooleanValue(getTextContent(attrs.getNamedItem("old-value-required"))));
                listenerConfig.setSynchronous(getBooleanValue(getTextContent(attrs.getNamedItem("synchronous"))));
                cacheSimpleConfig.addEntryListenerConfig(listenerConfig);
            }
        }
    }

    private void mapWanReplicationRefHandle(Node n, MapConfig mapConfig) {
        WanReplicationRef wanReplicationRef = new WanReplicationRef();
        String wanName = getAttribute(n, "name");
        wanReplicationRef.setName(wanName);
        for (Node wanChild : childElements(n)) {
            String wanChildName = cleanNodeName(wanChild);
            String wanChildValue = getTextContent(wanChild);
            if ("merge-policy".equals(wanChildName)) {
                wanReplicationRef.setMergePolicy(wanChildValue);
            } else if ("republishing-enabled".equals(wanChildName)) {
                wanReplicationRef.setRepublishingEnabled(getBooleanValue(wanChildValue));
            } else if ("filters".equals(wanChildName)) {
                handleWanFilters(wanChild, wanReplicationRef);
            }
        }
        mapConfig.setWanReplicationRef(wanReplicationRef);
    }

    private void mapIndexesHandle(Node n, MapConfig mapConfig) {
        for (Node indexNode : childElements(n)) {
            if ("index".equals(cleanNodeName(indexNode))) {
                NamedNodeMap attrs = indexNode.getAttributes();
                boolean ordered = getBooleanValue(getTextContent(attrs.getNamedItem("ordered")));
                String attribute = getTextContent(indexNode);
                mapConfig.addMapIndexConfig(new MapIndexConfig(attribute, ordered));
            }
        }
    }

    private void queryCacheIndexesHandle(Node n, QueryCacheConfig queryCacheConfig) {
        for (Node indexNode : childElements(n)) {
            if ("index".equals(cleanNodeName(indexNode))) {
                NamedNodeMap attrs = indexNode.getAttributes();
                boolean ordered = getBooleanValue(getTextContent(attrs.getNamedItem("ordered")));
                String attribute = getTextContent(indexNode);
                queryCacheConfig.addIndexConfig(new MapIndexConfig(attribute, ordered));
            }
        }
    }

    private void mapAttributesHandle(Node n, MapConfig mapConfig) {
        for (Node extractorNode : childElements(n)) {
            if ("attribute".equals(cleanNodeName(extractorNode))) {
                NamedNodeMap attrs = extractorNode.getAttributes();
                String extractor = getTextContent(attrs.getNamedItem("extractor"));
                String name = getTextContent(extractorNode);
                mapConfig.addMapAttributeConfig(new MapAttributeConfig(name, extractor));
            }
        }
    }

    private void mapEntryListenerHandle(Node n, MapConfig mapConfig) {
        for (Node listenerNode : childElements(n)) {
            if ("entry-listener".equals(cleanNodeName(listenerNode))) {
                NamedNodeMap attrs = listenerNode.getAttributes();
                boolean incValue = getBooleanValue(getTextContent(attrs.getNamedItem("include-value")));
                boolean local = getBooleanValue(getTextContent(attrs.getNamedItem("local")));
                String listenerClass = getTextContent(listenerNode);
                mapConfig.addEntryListenerConfig(new EntryListenerConfig(listenerClass, local, incValue));
            }
        }
    }

    private void mapPartitionLostListenerHandle(Node n, MapConfig mapConfig) {
        for (Node listenerNode : childElements(n)) {
            if ("partition-lost-listener".equals(cleanNodeName(listenerNode))) {
                String listenerClass = getTextContent(listenerNode);
                mapConfig.addMapPartitionLostListenerConfig(new MapPartitionLostListenerConfig(listenerClass));
            }
        }
    }

    private void mapQueryCacheHandler(Node n, MapConfig mapConfig) {
        for (Node queryCacheNode : childElements(n)) {
            if ("query-cache".equals(cleanNodeName(queryCacheNode))) {
                NamedNodeMap attrs = queryCacheNode.getAttributes();
                String cacheName = getTextContent(attrs.getNamedItem("name"));
                QueryCacheConfig queryCacheConfig = new QueryCacheConfig(cacheName);
                for (Node childNode : childElements(queryCacheNode)) {
                    String nodeName = cleanNodeName(childNode);
                    if ("entry-listeners".equals(nodeName)) {
                        for (Node listenerNode : childElements(childNode)) {
                            if ("entry-listener".equals(cleanNodeName(listenerNode))) {
                                NamedNodeMap listenerNodeAttributes = listenerNode.getAttributes();
                                boolean incValue = getBooleanValue(
                                        getTextContent(listenerNodeAttributes.getNamedItem("include-value")));
                                boolean local = getBooleanValue(getTextContent(listenerNodeAttributes.getNamedItem("local")));
                                String listenerClass = getTextContent(listenerNode);
                                queryCacheConfig.addEntryListenerConfig(new EntryListenerConfig(listenerClass, local, incValue));
                            }
                        }
                    } else {
                        String textContent = getTextContent(childNode);
                        if ("include-value".equals(nodeName)) {
                            boolean includeValue = getBooleanValue(textContent);
                            queryCacheConfig.setIncludeValue(includeValue);
                        } else if ("batch-size".equals(nodeName)) {
                            int batchSize = getIntegerValue("batch-size", textContent.trim());
                            queryCacheConfig.setBatchSize(batchSize);
                        } else if ("buffer-size".equals(nodeName)) {
                            int bufferSize = getIntegerValue("buffer-size", textContent.trim());
                            queryCacheConfig.setBufferSize(bufferSize);
                        } else if ("delay-seconds".equals(nodeName)) {
                            int delaySeconds = getIntegerValue("delay-seconds", textContent.trim());
                            queryCacheConfig.setDelaySeconds(delaySeconds);
                        } else if ("in-memory-format".equals(nodeName)) {
                            String value = textContent.trim();
                            queryCacheConfig.setInMemoryFormat(InMemoryFormat.valueOf(upperCaseInternal(value)));
                        } else if ("coalesce".equals(nodeName)) {
                            boolean coalesce = getBooleanValue(textContent);
                            queryCacheConfig.setCoalesce(coalesce);
                        } else if ("populate".equals(nodeName)) {
                            boolean populate = getBooleanValue(textContent);
                            queryCacheConfig.setPopulate(populate);
                        } else if ("indexes".equals(nodeName)) {
                            queryCacheIndexesHandle(childNode, queryCacheConfig);
                        } else if ("predicate".equals(nodeName)) {
                            queryCachePredicateHandler(childNode, queryCacheConfig);
                        } else if ("eviction".equals(nodeName)) {
                            queryCacheConfig.setEvictionConfig(getEvictionConfig(childNode));
                        }
                    }
                }
                mapConfig.addQueryCacheConfig(queryCacheConfig);
            }
        }
    }

    private void queryCachePredicateHandler(Node childNode, QueryCacheConfig queryCacheConfig) {
        NamedNodeMap predicateAttributes = childNode.getAttributes();
        String predicateType = getTextContent(predicateAttributes.getNamedItem("type"));
        String textContent = getTextContent(childNode);
        PredicateConfig predicateConfig = new PredicateConfig();
        if ("class-name".equals(predicateType)) {
            predicateConfig.setClassName(textContent);
        } else if ("sql".equals(predicateType)) {
            predicateConfig.setSql(textContent);
        }
        queryCacheConfig.setPredicateConfig(predicateConfig);
    }

    private int sizeParser(String value) {
        int size;
        if (value.length() < 2) {
            size = parseInt(value);
        } else {
            char last = value.charAt(value.length() - 1);
            int type = 0;
            if (last == 'g' || last == 'G') {
                type = 1;
            } else if (last == 'm' || last == 'M') {
                type = 2;
            }
            if (type == 0) {
                size = parseInt(value);
            } else if (type == 1) {
                size = parseInt(value.substring(0, value.length() - 1)) * THOUSAND_FACTOR;
            } else {
                size = parseInt(value.substring(0, value.length() - 1));
            }
        }
        return size;
    }

    private MapStoreConfig createMapStoreConfig(Node node) {
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        NamedNodeMap atts = node.getAttributes();
        for (int a = 0; a < atts.getLength(); a++) {
            Node att = atts.item(a);
            String value = getTextContent(att).trim();
            if ("enabled".equals(att.getNodeName())) {
                mapStoreConfig.setEnabled(getBooleanValue(value));
            } else if ("initial-mode".equals(att.getNodeName())) {
                InitialLoadMode mode = InitialLoadMode.valueOf(upperCaseInternal(getTextContent(att)));
                mapStoreConfig.setInitialLoadMode(mode);
            }
        }
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            if ("class-name".equals(nodeName)) {
                mapStoreConfig.setClassName(getTextContent(n).trim());
            } else if ("factory-class-name".equals(nodeName)) {
                mapStoreConfig.setFactoryClassName(getTextContent(n).trim());
            } else if ("write-delay-seconds".equals(nodeName)) {
                mapStoreConfig.setWriteDelaySeconds(getIntegerValue("write-delay-seconds", getTextContent(n).trim()
                ));
            } else if ("write-batch-size".equals(nodeName)) {
                mapStoreConfig.setWriteBatchSize(getIntegerValue("write-batch-size", getTextContent(n).trim()
                ));
            } else if ("write-coalescing".equals(nodeName)) {
                String writeCoalescing = getTextContent(n).trim();
                if (isNullOrEmpty(writeCoalescing)) {
                    mapStoreConfig.setWriteCoalescing(MapStoreConfig.DEFAULT_WRITE_COALESCING);
                } else {
                    mapStoreConfig.setWriteCoalescing(getBooleanValue(writeCoalescing));
                }
            } else if ("properties".equals(nodeName)) {
                fillProperties(n, mapStoreConfig.getProperties());
            }
        }
        return mapStoreConfig;
    }

    private QueueStoreConfig createQueueStoreConfig(Node node) {
        QueueStoreConfig queueStoreConfig = new QueueStoreConfig();
        NamedNodeMap atts = node.getAttributes();
        for (int a = 0; a < atts.getLength(); a++) {
            Node att = atts.item(a);
            String value = getTextContent(att).trim();
            if (att.getNodeName().equals("enabled")) {
                queueStoreConfig.setEnabled(getBooleanValue(value));
            }
        }
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            if ("class-name".equals(nodeName)) {
                queueStoreConfig.setClassName(getTextContent(n).trim());
            } else if ("factory-class-name".equals(nodeName)) {
                queueStoreConfig.setFactoryClassName(getTextContent(n).trim());
            } else if ("properties".equals(nodeName)) {
                fillProperties(n, queueStoreConfig.getProperties());
            }
        }
        return queueStoreConfig;
    }

    private void handleSSLConfig(Node node) {
        SSLConfig sslConfig = new SSLConfig();
        NamedNodeMap atts = node.getAttributes();
        Node enabledNode = atts.getNamedItem("enabled");
        boolean enabled = enabledNode != null && getBooleanValue(getTextContent(enabledNode).trim());
        sslConfig.setEnabled(enabled);

        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            if ("factory-class-name".equals(nodeName)) {
                sslConfig.setFactoryClassName(getTextContent(n).trim());
            } else if ("properties".equals(nodeName)) {
                fillProperties(n, sslConfig.getProperties());
            }
        }
        config.getNetworkConfig().setSSLConfig(sslConfig);
    }

    private void handleSocketInterceptorConfig(Node node) {
        SocketInterceptorConfig socketInterceptorConfig = parseSocketInterceptorConfig(node);
        config.getNetworkConfig().setSocketInterceptorConfig(socketInterceptorConfig);
    }

    private void handleTopic(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        TopicConfig tConfig = new TopicConfig();
        tConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            if (nodeName.equals("global-ordering-enabled")) {
                tConfig.setGlobalOrderingEnabled(getBooleanValue(getTextContent(n)));
            } else if ("message-listeners".equals(nodeName)) {
                for (Node listenerNode : childElements(n)) {
                    if ("message-listener".equals(cleanNodeName(listenerNode))) {
                        tConfig.addMessageListenerConfig(new ListenerConfig(getTextContent(listenerNode)));
                    }
                }
            } else if ("statistics-enabled".equals(nodeName)) {
                tConfig.setStatisticsEnabled(getBooleanValue(getTextContent(n)));
            } else if ("multi-threading-enabled".equals(nodeName)) {
                tConfig.setMultiThreadingEnabled(getBooleanValue(getTextContent(n)));
            }
        }
        config.addTopicConfig(tConfig);
    }

    private void handleReliableTopic(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        ReliableTopicConfig topicConfig = new ReliableTopicConfig(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            if ("read-batch-size".equals(nodeName)) {
                String batchSize = getTextContent(n);
                topicConfig.setReadBatchSize(getIntegerValue("read-batch-size", batchSize));
            } else if ("statistics-enabled".equals(nodeName)) {
                topicConfig.setStatisticsEnabled(getBooleanValue(getTextContent(n)));
            } else if ("topic-overload-policy".equals(nodeName)) {
                TopicOverloadPolicy topicOverloadPolicy = TopicOverloadPolicy.valueOf(upperCaseInternal(getTextContent(n)));
                topicConfig.setTopicOverloadPolicy(topicOverloadPolicy);
            } else if ("message-listeners".equals(nodeName)) {
                for (Node listenerNode : childElements(n)) {
                    if ("message-listener".equals(cleanNodeName(listenerNode))) {
                        topicConfig.addMessageListenerConfig(new ListenerConfig(getTextContent(listenerNode)));
                    }
                }
            }
        }
        config.addReliableTopicConfig(topicConfig);
    }

    private void handleJobTracker(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        JobTrackerConfig jConfig = new JobTrackerConfig();
        jConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("max-thread-size".equals(nodeName)) {
                jConfig.setMaxThreadSize(getIntegerValue("max-thread-size", value));
            } else if ("queue-size".equals(nodeName)) {
                jConfig.setQueueSize(getIntegerValue("queue-size", value));
            } else if ("retry-count".equals(nodeName)) {
                jConfig.setRetryCount(getIntegerValue("retry-count", value));
            } else if ("chunk-size".equals(nodeName)) {
                jConfig.setChunkSize(getIntegerValue("chunk-size", value));
            } else if ("communicate-stats".equals(nodeName)) {
                jConfig.setCommunicateStats(value == null || value.length() == 0
                        ? JobTrackerConfig.DEFAULT_COMMUNICATE_STATS : parseBoolean(value));
            } else if ("topology-changed-stategy".equals(nodeName)) {
                TopologyChangedStrategy topologyChangedStrategy = JobTrackerConfig.DEFAULT_TOPOLOGY_CHANGED_STRATEGY;
                for (TopologyChangedStrategy temp : TopologyChangedStrategy.values()) {
                    if (temp.name().equals(value)) {
                        topologyChangedStrategy = temp;
                    }
                }
                jConfig.setTopologyChangedStrategy(topologyChangedStrategy);
            }
        }
        config.addJobTrackerConfig(jConfig);
    }

    private void handleSemaphore(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        SemaphoreConfig sConfig = new SemaphoreConfig();
        sConfig.setName(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("initial-permits".equals(nodeName)) {
                sConfig.setInitialPermits(getIntegerValue("initial-permits", value));
            } else if ("backup-count".equals(nodeName)) {
                sConfig.setBackupCount(getIntegerValue("backup-count"
                        , value));
            } else if ("async-backup-count".equals(nodeName)) {
                sConfig.setAsyncBackupCount(getIntegerValue("async-backup-count"
                        , value));
            }
        }
        config.addSemaphoreConfig(sConfig);
    }

    private void handleRingbuffer(Node node) {
        Node attName = node.getAttributes().getNamedItem("name");
        String name = getTextContent(attName);
        RingbufferConfig rbConfig = new RingbufferConfig(name);
        for (Node n : childElements(node)) {
            String nodeName = cleanNodeName(n);
            String value = getTextContent(n).trim();
            if ("capacity".equals(nodeName)) {
                int capacity = getIntegerValue("capacity", value);
                rbConfig.setCapacity(capacity);
            } else if ("backup-count".equals(nodeName)) {
                int backupCount = getIntegerValue("backup-count", value);
                rbConfig.setBackupCount(backupCount);
            } else if ("async-backup-count".equals(nodeName)) {
                int asyncBackupCount = getIntegerValue("async-backup-count", value);
                rbConfig.setAsyncBackupCount(asyncBackupCount);
            } else if ("time-to-live-seconds".equals(nodeName)) {
                int timeToLiveSeconds = getIntegerValue("time-to-live-seconds", value);
                rbConfig.setTimeToLiveSeconds(timeToLiveSeconds);
            } else if ("in-memory-format".equals(nodeName)) {
                InMemoryFormat inMemoryFormat = InMemoryFormat.valueOf(upperCaseInternal(value));
                rbConfig.setInMemoryFormat(inMemoryFormat);
            }
        }
        config.addRingBufferConfig(rbConfig);
    }

    private void handleListeners(Node node) throws Exception {
        for (Node child : childElements(node)) {
            if ("listener".equals(cleanNodeName(child))) {
                String listenerClass = getTextContent(child);
                config.addListenerConfig(new ListenerConfig(listenerClass));
            }
        }
    }

    private void handlePartitionGroup(Node node) {
        NamedNodeMap atts = node.getAttributes();
        Node enabledNode = atts.getNamedItem("enabled");
        boolean enabled = enabledNode != null ? getBooleanValue(getTextContent(enabledNode)) : false;
        config.getPartitionGroupConfig().setEnabled(enabled);
        Node groupTypeNode = atts.getNamedItem("group-type");
        MemberGroupType groupType = groupTypeNode != null
                ? MemberGroupType.valueOf(upperCaseInternal(getTextContent(groupTypeNode)))
                : MemberGroupType.PER_MEMBER;
        config.getPartitionGroupConfig().setGroupType(groupType);
        for (Node child : childElements(node)) {
            if ("member-group".equals(cleanNodeName(child))) {
                handleMemberGroup(child);
            }
        }
    }

    private void handleMemberGroup(Node node) {
        MemberGroupConfig memberGroupConfig = new MemberGroupConfig();
        for (Node child : childElements(node)) {
            if ("interface".equals(cleanNodeName(child))) {
                String value = getTextContent(child);
                memberGroupConfig.addInterface(value);
            }
        }
        config.getPartitionGroupConfig().addMemberGroupConfig(memberGroupConfig);
    }

    private void handleSerialization(Node node) {
        SerializationConfig serializationConfig = parseSerialization(node);
        config.setSerializationConfig(serializationConfig);
    }

    private void handleManagementCenterConfig(Node node) {
        NamedNodeMap attrs = node.getAttributes();

        Node enabledNode = attrs.getNamedItem("enabled");
        boolean enabled = enabledNode != null && getBooleanValue(getTextContent(enabledNode));

        Node intervalNode = attrs.getNamedItem("update-interval");
        int interval = intervalNode != null ? getIntegerValue("update-interval",
                getTextContent(intervalNode)) : ManagementCenterConfig.UPDATE_INTERVAL;

        String url = getTextContent(node);
        ManagementCenterConfig managementCenterConfig = config.getManagementCenterConfig();
        managementCenterConfig.setEnabled(enabled);
        managementCenterConfig.setUpdateInterval(interval);
        managementCenterConfig.setUrl("".equals(url) ? null : url);
    }

    private void handleSecurity(Node node) throws Exception {
        NamedNodeMap atts = node.getAttributes();
        Node enabledNode = atts.getNamedItem("enabled");
        boolean enabled = enabledNode != null && getBooleanValue(getTextContent(enabledNode));
        config.getSecurityConfig().setEnabled(enabled);
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("member-credentials-factory".equals(nodeName)) {
                handleCredentialsFactory(child);
            } else if ("member-login-modules".equals(nodeName)) {
                handleLoginModules(child, true);
            } else if ("client-login-modules".equals(nodeName)) {
                handleLoginModules(child, false);
            } else if ("client-permission-policy".equals(nodeName)) {
                handlePermissionPolicy(child);
            } else if ("client-permissions".equals(nodeName)) {
                handleSecurityPermissions(child);
            } else if ("security-interceptors".equals(nodeName)) {
                handleSecurityInterceptors(child);
            }
        }
    }

    private void handleSecurityInterceptors(Node node) throws Exception {
        SecurityConfig cfg = config.getSecurityConfig();
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("interceptor".equals(nodeName)) {
                NamedNodeMap attrs = child.getAttributes();
                Node classNameNode = attrs.getNamedItem("class-name");
                String className = getTextContent(classNameNode);
                cfg.addSecurityInterceptorConfig(new SecurityInterceptorConfig(className));
            }
        }
    }

    private void handleMemberAttributes(Node node) {
        for (Node n : childElements(node)) {
            String name = cleanNodeName(n);
            if (!"attribute".equals(name)) {
                continue;
            }
            String attributeName = getTextContent(n.getAttributes().getNamedItem("name"));
            String attributeType = getTextContent(n.getAttributes().getNamedItem("type"));
            String value = getTextContent(n);
            if ("string".equals(attributeType)) {
                config.getMemberAttributeConfig().setStringAttribute(attributeName, value);
            } else if ("boolean".equals(attributeType)) {
                config.getMemberAttributeConfig().setBooleanAttribute(attributeName, parseBoolean(value));
            } else if ("byte".equals(attributeType)) {
                config.getMemberAttributeConfig().setByteAttribute(attributeName, Byte.parseByte(value));
            } else if ("double".equals(attributeType)) {
                config.getMemberAttributeConfig().setDoubleAttribute(attributeName, Double.parseDouble(value));
            } else if ("float".equals(attributeType)) {
                config.getMemberAttributeConfig().setFloatAttribute(attributeName, Float.parseFloat(value));
            } else if ("int".equals(attributeType)) {
                config.getMemberAttributeConfig().setIntAttribute(attributeName, parseInt(value));
            } else if ("long".equals(attributeType)) {
                config.getMemberAttributeConfig().setLongAttribute(attributeName, parseLong(value));
            } else if ("short".equals(attributeType)) {
                config.getMemberAttributeConfig().setShortAttribute(attributeName, Short.parseShort(value));
            } else {
                config.getMemberAttributeConfig().setStringAttribute(attributeName, value);
            }
        }
    }

    private void handleCredentialsFactory(Node node) throws Exception {
        NamedNodeMap attrs = node.getAttributes();
        Node classNameNode = attrs.getNamedItem("class-name");
        String className = getTextContent(classNameNode);
        SecurityConfig cfg = config.getSecurityConfig();
        CredentialsFactoryConfig credentialsFactoryConfig = new CredentialsFactoryConfig(className);
        cfg.setMemberCredentialsConfig(credentialsFactoryConfig);
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("properties".equals(nodeName)) {
                fillProperties(child, credentialsFactoryConfig.getProperties());
                break;
            }
        }
    }

    private void handleLoginModules(Node node, boolean member) throws Exception {
        SecurityConfig cfg = config.getSecurityConfig();
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("login-module".equals(nodeName)) {
                LoginModuleConfig lm = handleLoginModule(child);
                if (member) {
                    cfg.addMemberLoginModuleConfig(lm);
                } else {
                    cfg.addClientLoginModuleConfig(lm);
                }
            }
        }
    }

    private LoginModuleConfig handleLoginModule(Node node) throws Exception {
        NamedNodeMap attrs = node.getAttributes();
        Node classNameNode = attrs.getNamedItem("class-name");
        String className = getTextContent(classNameNode);
        Node usageNode = attrs.getNamedItem("usage");
        LoginModuleUsage usage = usageNode != null ? LoginModuleUsage.get(getTextContent(usageNode))
                : LoginModuleUsage.REQUIRED;
        LoginModuleConfig moduleConfig = new LoginModuleConfig(className, usage);
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("properties".equals(nodeName)) {
                fillProperties(child, moduleConfig.getProperties());
                break;
            }
        }
        return moduleConfig;
    }

    private void handlePermissionPolicy(Node node) throws Exception {
        NamedNodeMap attrs = node.getAttributes();
        Node classNameNode = attrs.getNamedItem("class-name");
        String className = getTextContent(classNameNode);
        SecurityConfig cfg = config.getSecurityConfig();
        PermissionPolicyConfig policyConfig = new PermissionPolicyConfig(className);
        cfg.setClientPolicyConfig(policyConfig);
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("properties".equals(nodeName)) {
                fillProperties(child, policyConfig.getProperties());
                break;
            }
        }
    }

    private void handleSecurityPermissions(Node node) throws Exception {
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            PermissionType type;
            if ("map-permission".equals(nodeName)) {
                type = PermissionType.MAP;
            } else if ("queue-permission".equals(nodeName)) {
                type = PermissionType.QUEUE;
            } else if ("multimap-permission".equals(nodeName)) {
                type = PermissionType.MULTIMAP;
            } else if ("topic-permission".equals(nodeName)) {
                type = PermissionType.TOPIC;
            } else if ("list-permission".equals(nodeName)) {
                type = PermissionType.LIST;
            } else if ("set-permission".equals(nodeName)) {
                type = PermissionType.SET;
            } else if ("lock-permission".equals(nodeName)) {
                type = PermissionType.LOCK;
            } else if ("atomic-long-permission".equals(nodeName)) {
                type = PermissionType.ATOMIC_LONG;
            } else if ("countdown-latch-permission".equals(nodeName)) {
                type = PermissionType.COUNTDOWN_LATCH;
            } else if ("semaphore-permission".equals(nodeName)) {
                type = PermissionType.SEMAPHORE;
            } else if ("id-generator-permission".equals(nodeName)) {
                type = PermissionType.ID_GENERATOR;
            } else if ("executor-service-permission".equals(nodeName)) {
                type = PermissionType.EXECUTOR_SERVICE;
            } else if ("transaction-permission".equals(nodeName)) {
                type = PermissionType.TRANSACTION;
            } else if ("all-permissions".equals(nodeName)) {
                type = PermissionType.ALL;
            } else {
                continue;
            }
            handleSecurityPermission(child, type);
        }
    }

    private void handleSecurityPermission(Node node, PermissionType type) throws Exception {
        SecurityConfig cfg = config.getSecurityConfig();
        NamedNodeMap attrs = node.getAttributes();
        Node nameNode = attrs.getNamedItem("name");
        String name = nameNode != null ? getTextContent(nameNode) : "*";
        Node principalNode = attrs.getNamedItem("principal");
        String principal = principalNode != null ? getTextContent(principalNode) : "*";
        PermissionConfig permConfig = new PermissionConfig(type, name, principal);
        cfg.addClientPermissionConfig(permConfig);
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("endpoints".equals(nodeName)) {
                handleSecurityPermissionEndpoints(child, permConfig);
            } else if ("actions".equals(nodeName)) {
                handleSecurityPermissionActions(child, permConfig);
            }
        }
    }

    private void handleSecurityPermissionEndpoints(Node node, PermissionConfig permConfig) throws Exception {
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("endpoint".equals(nodeName)) {
                permConfig.addEndpoint(getTextContent(child).trim());
            }
        }
    }

    private void handleSecurityPermissionActions(Node node, PermissionConfig permConfig) throws Exception {
        for (Node child : childElements(node)) {
            String nodeName = cleanNodeName(child);
            if ("action".equals(nodeName)) {
                permConfig.addAction(getTextContent(child).trim());
            }
        }
    }
}
