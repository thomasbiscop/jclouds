/**
 *
 * Copyright (C) 2009 Global Cloud Specialists, Inc. <info@globalcloudspecialists.com>
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 */
package org.jclouds.compute.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.jclouds.concurrent.ConcurrentUtils.awaitCompletion;
import static org.jclouds.concurrent.ConcurrentUtils.makeListenable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;
import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.jclouds.Constants;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Size;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.predicates.NodePredicates;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.compute.strategy.DestroyNodeStrategy;
import org.jclouds.compute.strategy.GetNodeMetadataStrategy;
import org.jclouds.compute.strategy.ListNodesStrategy;
import org.jclouds.compute.strategy.RebootNodeStrategy;
import org.jclouds.compute.strategy.RunNodesAndAddToSetStrategy;
import org.jclouds.compute.util.ComputeUtils;
import org.jclouds.compute.util.ComputeUtils.RunScriptOnNode;
import org.jclouds.domain.Location;
import org.jclouds.logging.Logger;
import org.jclouds.ssh.ExecResponse;
import org.jclouds.ssh.SshClient;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * 
 * @author Adrian Cole
 */
@Singleton
public class BaseComputeService implements ComputeService {

   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;

   protected final ComputeServiceContext context;
   protected final Provider<Set<? extends Image>> images;
   protected final Provider<Set<? extends Size>> sizes;
   protected final Provider<Set<? extends Location>> locations;
   protected final ListNodesStrategy listNodesStrategy;
   protected final GetNodeMetadataStrategy getNodeMetadataStrategy;
   protected final RunNodesAndAddToSetStrategy runNodesAndAddToSetStrategy;
   protected final RebootNodeStrategy rebootNodeStrategy;
   protected final DestroyNodeStrategy destroyNodeStrategy;
   protected final Provider<TemplateBuilder> templateBuilderProvider;
   protected final ComputeUtils utils;
   protected final ExecutorService executor;

   @Inject
   protected BaseComputeService(ComputeServiceContext context,
            Provider<Set<? extends Image>> images, Provider<Set<? extends Size>> sizes,
            Provider<Set<? extends Location>> locations, ListNodesStrategy listNodesStrategy,
            GetNodeMetadataStrategy getNodeMetadataStrategy,
            RunNodesAndAddToSetStrategy runNodesAndAddToSetStrategy,
            RebootNodeStrategy rebootNodeStrategy, DestroyNodeStrategy destroyNodeStrategy,
            Provider<TemplateBuilder> templateBuilderProvider, ComputeUtils utils,
            @Named(Constants.PROPERTY_USER_THREADS) ExecutorService executor) {
      this.context = checkNotNull(context, "context");
      this.images = checkNotNull(images, "images");
      this.sizes = checkNotNull(sizes, "sizes");
      this.locations = checkNotNull(locations, "locations");
      this.listNodesStrategy = checkNotNull(listNodesStrategy, "listNodesStrategy");
      this.getNodeMetadataStrategy = checkNotNull(getNodeMetadataStrategy,
               "getNodeMetadataStrategy");
      this.runNodesAndAddToSetStrategy = checkNotNull(runNodesAndAddToSetStrategy,
               "runNodesAndAddToSetStrategy");
      this.rebootNodeStrategy = checkNotNull(rebootNodeStrategy, "rebootNodeStrategy");
      this.destroyNodeStrategy = checkNotNull(destroyNodeStrategy, "destroyNodeStrategy");
      this.templateBuilderProvider = checkNotNull(templateBuilderProvider,
               "templateBuilderProvider");
      this.utils = checkNotNull(utils, "utils");
      this.executor = checkNotNull(executor, "executor");
   }

   @Override
   public ComputeServiceContext getContext() {
      return context;
   }

   @Override
   public Set<? extends NodeMetadata> runNodesWithTag(final String tag, int count,
            final Template template) throws RunNodesException {
      checkArgument(tag.indexOf('-') == -1, "tag cannot contain hyphens");
      checkNotNull(template.getLocation(), "location");
      logger.debug(">> running %d node%s tag(%s) location(%s) image(%s) size(%s) options(%s)",
               count, count > 1 ? "s" : "", tag, template.getLocation().getId(), template
                        .getImage().getId(), template.getSize().getId(), template.getOptions());
      final Set<NodeMetadata> nodes = Sets.newHashSet();
      final Map<NodeMetadata, Exception> badNodes = Maps.newLinkedHashMap();
      Map<?, ListenableFuture<Void>> responses = runNodesAndAddToSetStrategy.execute(tag, count,
               template, nodes, badNodes);
      Map<?, Exception> executionExceptions = awaitCompletion(responses, executor, null, logger,
               "starting nodes");
      if (executionExceptions.size() > 0 || badNodes.size() > 0) {
         throw new RunNodesException(tag, count, template, nodes, executionExceptions, badNodes);
      }
      return nodes;
   }

   @Override
   public void destroyNode(Location location, String id) {
      checkNotNull(location, "location");
      checkNotNull(id, "id");
      logger.debug(">> destroying node(%s/%s)", location.getId(), id);
      boolean successful = destroyNodeStrategy.execute(location, id);
      logger.debug("<< destroyed node(%s/%s) success(%s)", location.getId(), id, successful);
   }

   @Override
   public Set<? extends NodeMetadata> destroyNodesMatching(Predicate<NodeMetadata> filter) {
      logger.debug(">> destroying nodes matching(%s)", filter);
      Map<NodeMetadata, ListenableFuture<Void>> responses = Maps.newHashMap();
      final Set<NodeMetadata> destroyedNodes = Sets.newLinkedHashSet();
      for (final NodeMetadata node : nodesMatchingFilterAndNotTerminated(filter)) {
         responses.put(node, makeListenable(executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
               destroyNode(node.getLocation(), node.getId());
               destroyedNodes.add(node);
               return null;
            }
         }), executor));
      }
      awaitCompletion(responses, executor, null, logger, "destroying nodes");
      logger.debug("<< destroyed");
      return destroyedNodes;
   }

   private Iterable<? extends NodeMetadata> nodesMatchingFilterAndNotTerminated(
            Predicate<NodeMetadata> filter) {
      return Iterables.filter(detailsOnAllNodes(), Predicates.and(filter, Predicates
               .not(NodePredicates.TERMINATED)));
   }

   @Override
   public Set<? extends ComputeMetadata> listNodes() {
      logger.debug(">> listing nodes");
      Set<? extends ComputeMetadata> set = Sets.newLinkedHashSet(listNodesStrategy.list());
      logger.debug("<< list(%d)", set.size());
      return set;
   }

   @Override
   public Set<? extends NodeMetadata> listNodesDetailsMatching(Predicate<ComputeMetadata> filter) {
      checkNotNull(filter, "filter");
      logger.debug(">> listing node details matching(%s)", filter);
      Set<? extends NodeMetadata> set = Sets.newLinkedHashSet(listNodesStrategy
               .listDetailsOnNodesMatching(filter));
      logger.debug("<< list(%d)", set.size());
      return set;
   }

   @Override
   public Set<? extends Size> listSizes() {
      return sizes.get();
   }

   @Override
   public Set<? extends Image> listImages() {
      return images.get();
   }

   @Override
   public Set<? extends Location> listAssignableLocations() {
      return locations.get();
   }

   @Override
   public TemplateBuilder templateBuilder() {
      return templateBuilderProvider.get();
   }

   @Override
   public NodeMetadata getNodeMetadata(Location location, String id) {
      checkNotNull(location, "location");
      checkNotNull(id, "id");
      return getNodeMetadataStrategy.execute(location, id);
   }

   @Override
   public void rebootNode(Location location, String id) {
      checkNotNull(location, "location");
      checkNotNull(id, "id");
      logger.debug(">> rebooting node(%s/%s)", location.getId(), id);
      boolean successful = rebootNodeStrategy.execute(location, id);
      logger.debug("<< rebooted node(%s/%s) success(%s)", location.getId(), id, successful);
   }

   @Override
   public void rebootNodesMatching(Predicate<NodeMetadata> filter) {
      logger.debug(">> rebooting nodes matching(%s)", filter);

      Map<NodeMetadata, ListenableFuture<Void>> responses = Maps.newHashMap();
      for (final NodeMetadata node : nodesMatchingFilterAndNotTerminated(filter)) {
         responses.put(node, makeListenable(executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
               rebootNode(node.getLocation(), node.getId());
               return null;
            }
         }), executor));
      }
      awaitCompletion(responses, executor, null, logger, "rebooting nodes");
      logger.debug("<< rebooted");
   }

   /**
    * @throws RunScriptOnNodesException
    * @see #runScriptOnNodesMatching(Predicate, byte[],
    *      org.jclouds.compute.options.RunScriptOptions)
    * @see org.jclouds.compute.predicates.NodePredicates#runningWithTag(String)
    */
   @Override
   public Map<? extends NodeMetadata, ExecResponse> runScriptOnNodesMatching(
            Predicate<NodeMetadata> filter, byte[] runScript) throws RunScriptOnNodesException {
      return runScriptOnNodesMatching(filter, runScript, RunScriptOptions.NONE);
   }

   /**
    * Run the script on all nodes with the specific tag.
    * 
    * @param filter
    *           Predicate-based filter to define on which nodes the script is to be executed
    * @param runScript
    *           script to run in byte format. If the script is a string, use
    *           {@link String#getBytes()} to retrieve the bytes
    * @param options
    *           nullable options to how to run the script, whether to override credentials
    * @return map with node identifiers and corresponding responses
    * @throws RunScriptOnNodesException
    *            if anything goes wrong during script execution
    * 
    * @see org.jclouds.compute.predicates.NodePredicates#runningWithTag(String)
    */
   @Override
   public Map<NodeMetadata, ExecResponse> runScriptOnNodesMatching(Predicate<NodeMetadata> filter,
            final byte[] runScript, @Nullable final RunScriptOptions options)
            throws RunScriptOnNodesException {
      Iterable<? extends NodeMetadata> nodes = verifyParametersAndListNodes(filter, runScript,
               (options != null) ? options : RunScriptOptions.NONE);

      final Map<NodeMetadata, ExecResponse> execs = Maps.newHashMap();

      final Map<NodeMetadata, Exception> badNodes = Maps.newLinkedHashMap();

      Map<NodeMetadata, ListenableFuture<Void>> responses = Maps.newHashMap();

      for (final NodeMetadata node : nodes) {

         responses.put(node, makeListenable(executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
               try {
                  RunScriptOnNode callable;
                  if (options.isRunAsRoot())
                     callable = utils.runScriptOnNode(node, "computeserv", runScript);
                  else
                     callable = utils.runScriptOnNodeAsDefaultUser(node, "computeserv", runScript);
                  SshClient ssh = utils.createSshClientOncePortIsListeningOnNode(node);
                  try {
                     ssh.connect();
                     callable.setConnection(ssh, logger);
                     execs.put(node, callable.call());
                  } finally {
                     if (ssh != null)
                        ssh.disconnect();
                  }
               } catch (Exception e) {
                  badNodes.put(node, e);

               }
               return null;
            }
         }), executor));

      }
      Map<?, Exception> exceptions = awaitCompletion(responses, executor, null, logger,
               "starting nodes");
      if (exceptions.size() > 0 || badNodes.size() > 0) {
         throw new RunScriptOnNodesException(runScript, options, execs, exceptions, badNodes);
      }
      return execs;

   }

   private Iterable<? extends NodeMetadata> verifyParametersAndListNodes(
            Predicate<NodeMetadata> filter, byte[] runScript, final RunScriptOptions options) {
      checkNotNull(filter, "Filter must be provided");
      checkNotNull(runScript,
               "The script (represented by bytes array - use \"script\".getBytes() must be provided");
      checkNotNull(options, "options");

      Iterable<? extends NodeMetadata> nodes = Iterables.filter(detailsOnAllNodes(), filter);

      return Iterables.transform(nodes, new Function<NodeMetadata, NodeMetadata>() {

         @Override
         public NodeMetadata apply(NodeMetadata node) {

            checkArgument(node.getPublicAddresses().size() > 0, "no public ip addresses on node: "
                     + node);
            if (options.getOverrideCredentials() != null) {
               // override the credentials with provided to this method
               node = ComputeUtils.installNewCredentials(node, options.getOverrideCredentials());
            } else {
               // don't override
               checkNotNull(node.getCredentials(),
                        "If the default credentials need to be used, they can't be null");
               checkNotNull(node.getCredentials().account,
                        "Account name for ssh authentication must be "
                                 + "specified. Try passing RunScriptOptions with new credentials");
               checkNotNull(node.getCredentials().key,
                        "Key or password for ssh authentication must be "
                                 + "specified. Try passing RunScriptOptions with new credentials");
            }
            return node;
         }
      });
   }

   private Iterable<? extends NodeMetadata> detailsOnAllNodes() {
      return listNodesStrategy.listDetailsOnNodesMatching(NodePredicates.all());
   }
}