/*
 * ARX: Powerful Data Anonymization
 * Copyright 2012 - 2015 Florian Kohlmayer, Fabian Prasser
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

package org.deidentifier.arx.risk;

import org.deidentifier.arx.DataDefinition;
import org.deidentifier.arx.DataHandle;

import com.carrotsearch.hppc.IntIntOpenHashMap;
import com.carrotsearch.hppc.ObjectIntOpenHashMap;

/**
 * This class is the frontend for computing different dislcosure risk measures
 * for a given set of micro data
 * 
 * @author Michael Schneider
 * @author Fabian Prasser
 * @version 1.0
 */
public class RiskEstimator {
    
    /**
     * For hash tables
     * @author Fabian Prasser
     */
    private static class TupleWrapper {
        
        /** Hash code*/
        private final int        hashCode;
        /** Row */
        private final int        row;
        /** Indices */
        private final int[]      indices;
        /** Handle */
        private final DataHandle handle;

        /**
         * Constructor
         * @param handle
         * @param row
         */
        private TupleWrapper(DataHandle handle, int[] indices, int row) {
            this.handle = handle;
            this.row = row;
            this.indices = indices;
            
            int result = 1;
            for (int index : indices) {
                result = 31 * result + handle.getValue(row, index).hashCode();
            }
            this.hashCode = result;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            TupleWrapper other = (TupleWrapper)obj;
            for (int i = 0; i < indices.length; i++) {
                if (!handle.getValue(this.row, i).equals(handle.getValue(other.row, i))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Allows to include or exclude the SNB Model. If true, the SNBModel is
     * excluded
     */
    public boolean                  exlcudeSNB = true;

    /**
     * Size of biggest equivalence class in the data set
     */
    private int                     cMax;

    /**
     * Size of smallest equivalence class in the data set
     */
    private int                     cMin;

    /**
     * Map containing the equivalence class sizes (as keys) of the data set and
     * the corresponding frequency (as values) e.g. if the key 2 has value 3
     * then there are 3 equivalence classes of size two.
     */
    private final IntIntOpenHashMap eqClasses;

    /**
     * Sampling fraction, i.e. ration of sample size to population size
     */
    private double                  samplingFraction;

    /** The associated handle */
    private DataHandle              handle;

    /**
     * Creates a new instance of a class that allows to estimate different risk
     * measures for a given data set with a default sampling fraction of 0.1
     * 
     * @param handle
     *            This class provides access to dictionary encoded data.
     */
    public RiskEstimator(final DataHandle handle) {
        this(handle, 0.1d);
    }

    /**
     * Creates a new instance of a class that allows to estimate different risk
     * measures for a given data set
     * 
     * @param handle This class provides access to dictionary encoded data.
     * 
     * @param pi sampling fraction, defaults to 0.1
     */
    public RiskEstimator(final DataHandle handle, final double pi) {
        if ((pi == 0) || (pi > 1)) {
            this.samplingFraction = 0.1;
        } else {
            this.samplingFraction = pi;
        }

        // create map containing the equivalence class sizes (as keys) of the
        // data set and the corresponding frequency (as values)
        this.eqClasses = getEquivalenceClasses(handle);
        
        // Store reference to handle
        this.handle = handle;

        // set values for Cmin and Cmax
        initialize();
    }

    /**
     * The equivalence class risk denotes a risk measure which gives an average
     * of the whole data set. It is an aggregated, average risk for an
     * individual in the sample to be identifiable based on his
     * quasi-identifiers without further knowledge
     * 
     * @return the average risk of the file using as information only the data
     *         set (corresponding to a file level journalist risk)
     */
    public double getEquivalenceClassRisk() {
        final ModelEquivalenceClass equiModel = new ModelEquivalenceClass(eqClasses);
        return equiModel.getRisk();
    }

    /**
     * This functions takes a user defined data set and the defined
     * quasi-identifiers and marks the entries with the highest
     * re-identification risk. The estimate of the re-identification risk is
     * based solely on the data set and there is no population estimate that
     * plays into the calculation of the re-identification risk.<br>
     * <br>
     * As a side effect, this method may sort the data handle
     * 
     * @param definition
     *            Encapsulates a definition of the types of attributes contained
     *            in a given data set
     * @param handle
     *            This class provides access to dictionary encoded data.
     * @return An array, in which the i-th entry contains the size of the equivalence class in which
     *         the i-th data entry is contained
     */
    public int[] getEquivalenceClassSizes() {

        DataDefinition definition = handle.getDefinition();

        // Get indices of quasi identifiers
        final int[] indices = new int[definition.getQuasiIdentifyingAttributes().size()];
        int index = 0;
        for (final String attribute : definition.getQuasiIdentifyingAttributes()) {
            indices[index++] = handle.getColumnIndexOf(attribute);
        }

        // Calculate equivalence classes
        // TODO: Think about whether outliers should be handled separately
        ObjectIntOpenHashMap<TupleWrapper> map = new ObjectIntOpenHashMap<TupleWrapper>();
        for (int row = 0; row < handle.getNumRows(); row++) {
            TupleWrapper tuple = new TupleWrapper(handle, indices, row);
            map.putOrAdd(tuple, 1, 1);
        }
        
        // Build result
        int[] result = new int[this.handle.getNumRows()];
        for (int row = 0; row < handle.getNumRows(); row++) {
            TupleWrapper tuple = new TupleWrapper(handle, indices, row);
            result[row] = map.get(tuple);
        }
        return result;
    }

    /**
     * The highest individual risk return the highest risk for an individual
     * entry in the data set. For a sample unique entry the risk is maximal
     * (one). This is to say, that it is possible to re-identify an individual
     * with certainty if there is certain knowledge about the quasi-identifying
     * attributes of this individual. Nevertheless, this measure will normally
     * strongly overestimate the actual re-identification risk since not every
     * sample unique entry will be population unique
     * 
     * @return the highest risk for a single entry in the data set.
     */
    public double getHighestIndividualRisk() {
        if (cMin != 0) {
            return (1 / (double) cMin);
        } else {
            return Double.NaN;
        }
    }

    /**
     * Number of entries that are most likely to be re-identified based (risk
     * corresponds to the HighestIndividualRisk) on the sample information
     * 
     * @return number of entries that belong to highest risk category
     */
    public double getHighestRiskAffected() {
        if (cMin != 0) {
            return eqClasses.get(cMin);
        } else {
            return Double.NaN;
        }

    }

    /**
     * Returns the size of the largest equivalence class
     * @return
     */
    public int getMaximalClassSize() {
        return this.cMax;
    }

    /**
     * Returns the size of the smallest equivalence class
     * @return
     */
    public int getMinimalClassSize() {
        return this.cMin;
    }
    
    /**
     * Returns the percentage of sample uniques
     * @return
     * @throws IllegalStateException
     */
    public double getSampleUniquesRisk() throws IllegalStateException {
        return (double)eqClasses.get(1) / (double)handle.getNumRows();
    }

    /**
     * This class computes the percentage of population uniques, i.e. the
     * entries that are unique in a population. The population parameters are
     * estimated according to different models. We use a model proposed by
     * Dankar et al. (Fida Dankar, Khaled El Emam, Angelica Neisa, and Tyson
     * Roffey. Estimating the re-identification risk of clinical data sets. BMC
     * Medical Informatics and Decision Making, 12(1):66, 2012.) that has been
     * modified for practical purposes to estimate the population and its
     * parameters and to compute the number of population uniques.
     * 
     * @return the percentage of population uniques as an estimate based on our
     *         data set. This is a common measure for disclosure risk.
     */
    public double getPopulationUniquesRisk() throws IllegalStateException {
        double result;

        if (exlcudeSNB) {
            /*
             * Selection rule, according to Danker et al, 2010, modified to
             * exclude the SNB model and anonymized data
             */
            if (eqClasses.containsKey(1) && !eqClasses.containsKey(2)) {
                final ModelZayatz model = new ModelZayatz(samplingFraction, eqClasses);
                result = model.getRisk();
                return result;
            }
            if (!eqClasses.containsKey(1)) {
                throw new IllegalStateException("The data set does not contain any sample uniques! Computing Population Uniques not possible!");
            }

            if (samplingFraction <= 0.1) {
                final ModelPitman model = new ModelPitman(samplingFraction, eqClasses);
                result = model.getRisk();
                if (Double.isNaN(result)) {
                    final ModelZayatz zayatzModel = new ModelZayatz(samplingFraction, eqClasses);
                    result = zayatzModel.getRisk();
                }
            } else {
                final ModelZayatz model = new ModelZayatz(samplingFraction, eqClasses);
                result = model.getRisk();
                if (Double.isNaN(result)) {
                    final ModelPitman pitmanModel = new ModelPitman(samplingFraction, eqClasses);
                    result = pitmanModel.getRisk();
                }
            }
            return result;
        } else {

            /*
             * Selection rule, according to Danker et al, 2010
             */
            if (eqClasses.containsKey(1) && !eqClasses.containsKey(2)) {
                final ModelZayatz model = new ModelZayatz(samplingFraction, eqClasses);
                result = model.getRisk();
                return result;
            }
            if (!eqClasses.containsKey(1)) {
                throw new IllegalStateException("The data set does not contain any sample uniques! Computing Population Uniques not possible!");
            }

            if (samplingFraction <= 0.1) {
                final ModelPitman model = new ModelPitman(samplingFraction, eqClasses);
                result = model.getRisk();
                if (Double.isNaN(result)) {
                    final ModelZayatz zayatzModel = new ModelZayatz(samplingFraction, eqClasses);
                    result = zayatzModel.getRisk();
                }
            } else {
                final ModelZayatz model = new ModelZayatz(samplingFraction, eqClasses);
                final ModelSNB model2 = new ModelSNB(samplingFraction, eqClasses);
                result = model.getRisk();
                final double result2 = model2.getRisk();
                if (Double.isNaN(result)) {
                    result = result2;
                    if (Double.isNaN(result)) {
                        final ModelPitman pitmanModel = new ModelPitman(samplingFraction, eqClasses);
                        result = pitmanModel.getRisk();
                        return result;
                    }
                }
                if (Double.isNaN(result2)) {
                    if (Double.isNaN(result)) {
                        final ModelPitman pitmanModel = new ModelPitman(samplingFraction, eqClasses);
                        result = pitmanModel.getRisk();
                    }
                    return result;
                } else {
                    if (result2 > result) {
                        return result;
                    } else {
                        return result2;
                    }
                }

            }
            return result;
        }
    }

    /**
     * Returns whether the SNB model is excluded from the risk model
     */
    public boolean isExlcudeSNBModel() {
        return exlcudeSNB;
    }

    /**
     * Sets whether the SNB model should be excluded from the risk model
     */
    public void setExlcudeSNBModel(boolean exlcudeSNB) {
        this.exlcudeSNB = exlcudeSNB;
    }

    /**
     * This functions takes a user defined data set and the defined
     * quasi-identifiers and extracts the size and frequency of all equivalence
     * classes
     * 
     * @param handle
     *            This class provides access to dictionary encoded data.
     * 
     * @return Map containing the equivalence class sizes (as keys) of the data set and
     *         the corresponding frequency (as values) e.g. if the key 2 has value 3
     *         then there are 3 equivalence classes of size two.
     */
    private IntIntOpenHashMap getEquivalenceClasses(final DataHandle handle) {

        DataDefinition definition = handle.getDefinition();

        // Get indices of quasi identifiers
        final int[] indices = new int[definition.getQuasiIdentifyingAttributes().size()];
        int index = 0;
        for (final String attribute : definition.getQuasiIdentifyingAttributes()) {
            indices[index++] = handle.getColumnIndexOf(attribute);
        }

        // Calculate equivalence classes
        // TODO: Think about whether outliers should be handled separately
        ObjectIntOpenHashMap<TupleWrapper> map = new ObjectIntOpenHashMap<TupleWrapper>();
        for (int row = 0; row < handle.getNumRows(); row++) {
            TupleWrapper tuple = new TupleWrapper(handle, indices, row);
            map.putOrAdd(tuple, 1, 1);
        }

        // Build result
        IntIntOpenHashMap result = new IntIntOpenHashMap();
        final int[] values = map.values;
        final boolean[] states = map.allocated;
        for (int i = 0; i < states.length; i++) {
            if (states[i]) {
                result.putOrAdd(values[i], 1, 1);
            }
        }
        return result;
    }

    /**
     * Sets values of Cmin and Cmax, giving range of equivalence class sizes
     */
    private void initialize() {
        cMin = Integer.MAX_VALUE;
        cMax = 0;
        final int[] keys = eqClasses.keys;
        final boolean[] states = eqClasses.allocated;
        for (int i = 0; i < states.length; i++) {
            if (states[i]) {
                int key = keys[i];
                if (cMin > key) {
                    cMin = key;
                }
                if (cMax < key) {
                    cMax = key;
                }
            }
        }
        if (cMin == Integer.MAX_VALUE) {
            cMin = 0;
        }
    }
}
