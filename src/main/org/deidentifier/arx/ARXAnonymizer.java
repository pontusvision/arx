/*
 * ARX: Efficient, Stable and Optimal Data Anonymization
 * Copyright (C) 2012 - 2013 Florian Kohlmayer, Fabian Prasser
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.deidentifier.arx;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.deidentifier.arx.algorithm.AbstractAlgorithm;
import org.deidentifier.arx.algorithm.FLASHAlgorithm;
import org.deidentifier.arx.algorithm.FLASHStrategy;
import org.deidentifier.arx.criteria.HierarchicalDistanceTCloseness;
import org.deidentifier.arx.criteria.KAnonymity;
import org.deidentifier.arx.criteria.LDiversity;
import org.deidentifier.arx.criteria.TCloseness;
import org.deidentifier.arx.framework.check.INodeChecker;
import org.deidentifier.arx.framework.check.NodeChecker;
import org.deidentifier.arx.framework.data.DataManager;
import org.deidentifier.arx.framework.data.Dictionary;
import org.deidentifier.arx.framework.data.GeneralizationHierarchy;
import org.deidentifier.arx.framework.lattice.Lattice;
import org.deidentifier.arx.framework.lattice.LatticeBuilder;
import org.deidentifier.arx.metric.Metric;

/**
 * This class offers several methods to define parameters and execute the ARX
 * algorithm.
 * 
 * @author Prasser, Kohlmayer
 */
public class ARXAnonymizer {

    /**
     * Temporary result of the ARX algorithm.
     * 
     * @author Prasser, Kohlmayer
     */
    class Result {

        /** The checker. */
        final INodeChecker checker;

        /** The metric. */
        final Metric<?>    metric;

        /** The lattice. */
        final Lattice      lattice;

        /**
         * Creates a new instance.
         * 
         * @param metric
         *            the metric
         * @param checker
         *            the checker
         * @param lattice
         *            the lattice
         */
        private Result(final Metric<?> metric, final INodeChecker checker, final Lattice lattice) {
            this.metric = metric;
            this.checker = checker;
            this.lattice = lattice;
        }
    }

    /** Remove outliers? */
    private boolean       removeOutliers        = true;

    /** Snapshot size. */
    private double        snapshotSizeDataset   = 0.2d;

    /** Snapshot size snapshot */
    private double        snapshotSizeSnapshot  = 0.8d;

    /** History size. */
    private int           historySize           = 200;

    /** The string to insert for outliers. */
    private String        suppressionString     = "*";

    /** The listener, if any. */
    private ARXListener listener              = null;

    /**
     * Creates a new anonymizer with the default configuration
     */
    public ARXAnonymizer() {
        // Empty by design
    }

    /**
     * Creates a new anonymizer with the given configuration.
     * 
     * @param historySize The maximum number of snapshots stored in the buffer [default=200]
     * @param snapshotSizeDataset The maximum relative size of a snapshot compared to the dataset [default=0.2]
     * @param snapshotSizeSnapshot The maximum relative size of a snapshot compared to its predecessor [default=0.8]
     */
    public ARXAnonymizer(final int historySize, final double snapshotSizeDataset, final double snapshotSizeSnapshot) {
        if (historySize<0) 
            throw new RuntimeException("History size must be >=0");
        this.historySize = historySize;
        if (snapshotSizeDataset<=0 || snapshotSizeDataset>=1) 
            throw new RuntimeException("SnapshotSizeDataset must be >0 and <1");
        this.snapshotSizeDataset = snapshotSizeDataset;
        if (snapshotSizeSnapshot<=0 || snapshotSizeSnapshot>=1) 
            throw new RuntimeException("snapshotSizeSnapshot must be >0 and <1");
        this.snapshotSizeSnapshot = snapshotSizeSnapshot;
    }


    /**
     * Creates a new anonymizer with the given configuration.
     * 
     * @param suppressionString The string inserted for suppressed values
     */
    public ARXAnonymizer(final String suppressionString) {
        this.suppressionString = suppressionString;
    }

    /**
     * Creates a new anonymizer with the given configuration.
     * 
     * @param suppressionString The string inserted for suppressed values
     * @param historySize The maximum number of snapshots stored in the buffer [default=200]
     * @param snapshotSizeDataset The maximum relative size of a snapshot compared to the dataset [default=0.2]
     * @param snapshotSizeSnapshot The maximum relative size of a snapshot compared to its predecessor [default=0.8]
     */
    public ARXAnonymizer(final String suppressionString, final int historySize, final double snapshotSizeDataset, final double snapshotSizeSnapshot) {
        this.suppressionString = suppressionString;
        if (historySize<0) 
            throw new RuntimeException("History size must be >=0");
        this.historySize = historySize;
        if (snapshotSizeDataset<=0 || snapshotSizeDataset>=1) 
            throw new RuntimeException("SnapshotSizeDataset must be >0 and <1");
        this.snapshotSizeDataset = snapshotSizeDataset;
        if (snapshotSizeSnapshot<=0 || snapshotSizeSnapshot>=1) 
            throw new RuntimeException("snapshotSizeSnapshot must be >0 and <1");
        this.snapshotSizeSnapshot = snapshotSizeSnapshot;
    }

    /**
     * Performs data anonymization
     * @param data The data
     * @param config The privacy config
     * @return ARXResult
     * @throws IOException
     */
    public ARXResult anonymize(final Data data, final ARXConfiguration config) throws IOException {

        if (data == null) { throw new NullPointerException("Data cannot be null!"); }
        if (config.containsCriterion(LDiversity.class) ||
            config.containsCriterion(TCloseness.class)){
            if (data.getDefinition().getSensitiveAttributes().size() == 0) { throw new IllegalArgumentException("You need to specify a sensitive attribute!"); }
        }

        // Obtain handle
        final DataHandle handle = data.getHandle();

        // Check
        if (!(handle instanceof DataHandleInput)) { throw new IllegalArgumentException("Invalid data handle provided!"); }
        checkBeforeEncoding(config.getAllowedOutliers(), handle.getDefinition().getHierarchies());

        final DataManager manager = prepareDataManager(handle, config);

        config.initialize(manager);

        final long time = System.currentTimeMillis();

        final Result result = anonymizeInternal(config, manager);

        final DataHandleOutput outHandle = new DataHandleOutput(result.metric,
                                                                manager,
                                                                result.checker,
                                                                System.currentTimeMillis() - time,
                                                                suppressionString,
                                                                handle.getDefinition(),
                                                                result.lattice,
                                                                removeOutliers,
                                                                config);
        // Pairing
        outHandle.associate(handle);
        handle.associate(outHandle);

        return outHandle;
    }

    protected Result anonymizeInternal(final ARXConfiguration config, final DataManager manager) {

        // Check
        checkAfterEncoding(config, manager);

        // Build the lattice
        final Lattice lattice = new LatticeBuilder(manager.getMaxLevels(), manager.getMinLevels(), manager.getHierachyHeights()).build();

        // Attach the listener
        lattice.setListener(listener);

        // Build a node checker
        final INodeChecker checker = new NodeChecker(manager, config.getMetric(), config, historySize, snapshotSizeDataset, snapshotSizeSnapshot);

        // Initialize the metric
        config.getMetric().initialize(manager.getDataQI(), manager.getHierarchies());

        // Initialize the ARX strategy
        final FLASHStrategy strategy = new FLASHStrategy(lattice, manager.getHierarchies());

        // Build an algorithm instance
        final AbstractAlgorithm algorithm = new FLASHAlgorithm(lattice, checker, strategy);

        // Attach the listener
        algorithm.setListener(listener);

        // Execute
        algorithm.traverse();

        // Return the result
        final Result result = new Result(config.getMetric(), checker, lattice);

        return result;
    }

    /**
     * Performs some sanity checks.
     * 
     * @param manager
     *            the manager
     */
    private void checkAfterEncoding(final ARXConfiguration config, final DataManager manager) {

        if (config.containsCriterion(KAnonymity.class)){
            KAnonymity c = config.getCriterion(KAnonymity.class);
            if ((c.getK() > manager.getDataQI().getDataLength()) || (c.getK() < 1)) { 
                throw new IllegalArgumentException("Group size k " + c.getK() + " musst be positive and less or equal than the number of rows " + manager.getDataQI().getDataLength()); 
            }
        }
        if (config.containsCriterion(LDiversity.class)){
            LDiversity c = config.getCriterion(LDiversity.class);
            if ((c.getL() > manager.getDataQI().getDataLength()) || (c.getL() < 1)) { 
                throw new IllegalArgumentException("Group size k " + c.getL() + " musst be positive and less or equal than the number of rows " + manager.getDataQI().getDataLength()); 
            }
        }
        
        // Check whether all hierarchies are monotonic
        for (final GeneralizationHierarchy hierarchy : manager.getHierarchies()) {
            if (!hierarchy.isMonotonic()) { throw new IllegalArgumentException("The hierarchy for the attribute '" + hierarchy.getName() + "' is not monotonic!"); }
        }

        // check min and max sizes
        final int[] hierarchyHeights = manager.getHierachyHeights();
        final int[] minLevels = manager.getMinLevels();
        final int[] maxLevels = manager.getMaxLevels();

        for (int i = 0; i < hierarchyHeights.length; i++) {
            if (minLevels[i] > (hierarchyHeights[i] - 1)) { throw new IllegalArgumentException("Invalid minimum generalization for attribute '" + manager.getHierarchies()[i].getName() + "': " +
                                                                                               minLevels[i] + " > " + (hierarchyHeights[i] - 1)); }
            if (minLevels[i] < 0) { throw new IllegalArgumentException("The minimum generalization for attribute '" + manager.getHierarchies()[i].getName() + "' has to be positive!"); }
            if (maxLevels[i] > (hierarchyHeights[i] - 1)) { throw new IllegalArgumentException("Invalid maximum generalization for attribute '" + manager.getHierarchies()[i].getName() + "': " +
                                                                                               maxLevels[i] + " > " + (hierarchyHeights[i] - 1)); }
            if (maxLevels[i] < minLevels[i]) { throw new IllegalArgumentException("The minimum generalization for attribute '" + manager.getHierarchies()[i].getName() +
                                                                                  "' has to be lower than or requal to the defined maximum!"); }
        }
    }

    /**
     * Performs some sanity checks.
     * 
     * @param relativeMaxOutliers
     *            the allowed maximal number of outliers
     * @param hierarchies
     *            the hierarchies
     */
    private void checkBeforeEncoding(final double relativeMaxOutliers, final Map<String, String[][]> hierarchies) {

        // Perform sanity checks
        if ((relativeMaxOutliers < 0d) || (relativeMaxOutliers >= 1d)) { throw new IllegalArgumentException("Suppression rate " + relativeMaxOutliers + "must be in [0,1["); }
        if (hierarchies.size() > 15) { throw new IllegalArgumentException("The curse of dimensionality strikes. Too many quasi-identifiers: " + hierarchies.size()); }
        if (hierarchies.size() == 0) { throw new IllegalArgumentException("You need to specify at least one quasi identifier!"); }

    }

    /**
     * Returns the maximum number of snapshots allowed to store in the history.
     * 
     * @return The size
     */
    public int getHistorySize() {
        return historySize;
    }

    /**
     * Gets the snapshot size.
     * 
     * @return The maximum size of a snapshot relative to the dataset size
     */
    public double getMaximumSnapshotSizeDataset() {
        return snapshotSizeDataset;
    }

    /**
     * Gets the snapshot size.
     * 
     * @return The maximum size of a snapshot relative to the previous snapshot
     *         size
     */
    public double getMaximumSnapshotSizeSnapshot() {
        return snapshotSizeSnapshot;
    }

    /**
     * Returns the string with which outliers are replaced.
     * 
     * @return the relativeMaxOutliers string
     */
    public String getSuppressionString() {
        return suppressionString;
    }

    /**
     * Does the anonymizer remove outliers from the dataset?
     * 
     * @return
     */
    public boolean isRemoveOutliers() {
        return removeOutliers;
    }

    /**
     * Prepares the data manager.
     * 
     * @param handle
     *            the handle
     * @param config
     *            the config
     * @return the data manager
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private DataManager prepareDataManager(final DataHandle handle, final ARXConfiguration config) throws IOException {

        // Extract data
        final String[] header = ((DataHandleInput) handle).header;
        final int[][] dataArray = ((DataHandleInput) handle).data;
        final Dictionary dictionary = ((DataHandleInput) handle).dictionary;
        final DataManager manager = new DataManager(header, dataArray, dictionary, handle.getDefinition(), config.getCriteria());
        return manager;
    }

    /**
     * Sets the maximum number of snapshots allowed to store in the history.
     * 
     * @param historySize
     *            The size
     */
    public void setHistorySize(final int historySize) {
        if (historySize < 1) { throw new IllegalArgumentException("history size must be positive and not 0"); }
        this.historySize = historySize;
    }

    /**
     * Sets a listener.
     * 
     * @param listener
     *            the new listener, if any
     */
    public void setListener(final ARXListener listener) {
        this.listener = listener;
    }

    /**
     * Sets the maximum size of a snapshot relative to the dataset size.
     * 
     * @param snapshotSizeDataset
     *            The size
     */
    public void setMaximumSnapshotSizeDataset(final double snapshotSize) {
        // Perform sanity checks
        if ((snapshotSize <= 0d) || (snapshotSize > 1d)) { throw new IllegalArgumentException("Snapshot size " + snapshotSize + "must be in [0,1]"); }
        snapshotSizeDataset = snapshotSize;
    }

    /**
     * Sets the maximum size of a snapshot relative to the previous snapshot
     * 
     * @param snapshotSizeSnapshot
     *            The size
     */
    public void setMaximumSnapshotSizeSnapshot(final double snapshotSizeSnapshot) {
        // Perform sanity checks
        if ((snapshotSizeSnapshot <= 0d) || (snapshotSizeSnapshot > 1d)) { throw new IllegalArgumentException("Snapshot size " + snapshotSizeSnapshot + "must be in [0,1]"); }
        this.snapshotSizeSnapshot = snapshotSizeSnapshot;
    }

    /**
     * Set whether the anonymizer should remove outliers
     * 
     * @param value
     */
    public void setRemoveOutliers(final boolean value) {
        removeOutliers = value;
    }

    /**
     * Sets the string with which suppressed values are to be replaced.
     * 
     * @param suppressionString
     *            The relativeMaxOutliers string
     */
    public void setSuppressionString(final String suppressionString) {
        if (suppressionString == null) { throw new NullPointerException("suppressionString must not be null"); }
        this.suppressionString = suppressionString;
    }
}
