/*
 * ARX: Powerful Data Anonymization
 * Copyright 2012 - 2016 Fabian Prasser, Florian Kohlmayer and contributors
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

package org.deidentifier.arx.metric.v2;

import org.deidentifier.arx.ARXConfiguration;
import org.deidentifier.arx.ARXFinancialConfiguration;
import org.deidentifier.arx.DataDefinition;
import org.deidentifier.arx.framework.check.groupify.HashGroupify;
import org.deidentifier.arx.framework.check.groupify.HashGroupifyEntry;
import org.deidentifier.arx.framework.data.Data;
import org.deidentifier.arx.framework.data.DataManager;
import org.deidentifier.arx.framework.data.GeneralizationHierarchy;
import org.deidentifier.arx.framework.lattice.Transformation;
import org.deidentifier.arx.metric.InformationLossWithBound;
import org.deidentifier.arx.metric.MetricConfiguration;
import org.deidentifier.arx.metric.MetricConfiguration.MetricConfigurationAttackerModel;
import org.deidentifier.arx.risk.RiskModelFinancial;

/**
 * This class implements a prototype model which maximizes publisher benefit as proposed in:<br>
 * A Game Theoretic Framework for Analyzing Re-Identification Risk.
 * Zhiyu Wan, Yevgeniy Vorobeychik, Weiyi Xia, Ellen Wright Clayton,
 * Murat Kantarcioglu, Ranjit Ganta, Raymond Heatherly, Bradley A. Malin
 * PLOS|ONE. 2015.
 * 
 * @author Fabian Prasser
 */
public class MetricSDNMPublisherPayout extends AbstractMetricSingleDimensional {

    /** SUID. */
    private static final long         serialVersionUID = 5729454129866471107L;

    /** Configuration for the Stackelberg game */
    private ARXFinancialConfiguration config;

    /** Domain shares for each dimension. */
    private DomainShare[]             shares;

    /** MaxIL */
    private double                    maxIL;

    /** Risk model */
    private RiskModelFinancial        modelRisk;

    /** Journalist attacker model */
    private boolean                   journalistAttackerModel;

    /**
     * Creates a new instance
     * @param journalistAttackerModel If set to true, the journalist attacker model will be assumed, 
     *                                the prosecutor model will be assumed, otherwise
     * @param gsFactor A factor [0,1] weighting generalization and suppression.
     *            The default value is 0.5, which means that generalization
     *            and suppression will be treated equally. A factor of 0
     *            will favor suppression, and a factor of 1 will favor
     *            generalization. The values in between can be used for
     *            balancing both methods.
     */
    public MetricSDNMPublisherPayout(boolean journalistAttackerModel, double gsFactor) {
        super(false, false, false, gsFactor);
        this.journalistAttackerModel = journalistAttackerModel;
    }
    
    /**
     * Creates a new instance. Default constructor which treats all transformation methods equally.
     * @param journalistAttackerModel If set to true, the journalist attacker model will be assumed, 
     *                                the prosecutor model will be assumed, otherwise
     */
    public MetricSDNMPublisherPayout(boolean journalistAttackerModel) {
       this(journalistAttackerModel, 0.5d);
    }
    
    @Override
    public ILSingleDimensional createMaxInformationLoss() {
        Double rows = getNumTuples();
        if (rows == null) {
            throw new IllegalStateException("Metric must be initialized first");
        } else {
            return new ILSingleDimensional(rows * this.config.getPublisherBenefit());
        }
    }

    @Override
    public ILSingleDimensional createMinInformationLoss() {
        return new ILSingleDimensional(0d);
    }

    /**
     * Returns the configuration of this metric.
     * 
     * @return
     */
    public MetricConfiguration getConfiguration() {
        return new MetricConfiguration(false, 
                                       super.getGeneralizationSuppressionFactor(), // gs-factor
                                       false, 
                                       0.0d, 
                                       this.getAggregateFunction(),
                                       this.journalistAttackerModel ? MetricConfigurationAttackerModel.JOURNALIST : 
                                                                      MetricConfigurationAttackerModel.PROSECUTOR);
    }
    
    /**
     * Returns the financial configuration
     */
    public ARXFinancialConfiguration getFinancialConfiguration() {
        return this.config;
    }

    @Override
    public String getName() {
        return "Publisher benefit";
    }

    @Override
    public boolean isGSFactorSupported() {
        return true;
    }

    /**
     * Returns whether the journalist attacker model is being assumed.
     * @return
     */
    public boolean isJournalistAttackerModel() {
        return this.journalistAttackerModel;
    }

    /**
     * Returns whether the prosecutor attacker model is being assumed.
     * @return
     */
    public boolean isProsecutorAttackerModel() {
        return !this.journalistAttackerModel;
    }

    @Override
    public String toString() {
        String result = "PublisherBenefit (" + (journalistAttackerModel ? "Journalist" : "Prosecutor");
        if (config == null) {
            result += ")";
        } else {
            result += ", Benefit=" + config.getPublisherBenefit() + ")";
        }
        return result;
    }

    /**
     * Returns the success probability. If the game is configured to use the journalist risk, 
     * but no population table is available, we silently default to the prosecutor model.
     * @param entry
     * @return
     */
    private double getSuccessProbability(HashGroupifyEntry entry) {
        return !journalistAttackerModel || entry.pcount == 0 ? 1d / entry.count : 1d / entry.pcount;
    }

    /**
     * Returns the information loss for the according class. This is an exact copy of: 
     * @see MetricSDNMEntropyBasedInformationLoss.getEntropyBasedInformationLoss(Transformation, HashGroupifyEntry)
     */
    protected double getEntropyBasedInformationLoss(Transformation transformation, HashGroupifyEntry entry) {
        int[] generalization = transformation.getGeneralization();
        double infoLoss = 1d;
        for (int dimension = 0; dimension < shares.length; dimension++) {
            int value = entry.key[dimension];
            int level = generalization[dimension];
            infoLoss *= shares[dimension].getShare(value, level);
        }
        return Math.log10(infoLoss) / maxIL + 1d;
    }
    @Override
    protected ILSingleDimensionalWithBound getInformationLossInternal(Transformation transformation, HashGroupify groupify) {
        
        // Compute
        double real = 0;
        double bound = 0;
        double gFactor = super.getGeneralizationFactor();
        double sFactor = super.getSuppressionFactor();
        HashGroupifyEntry entry = groupify.getFirstEquivalenceClass();
        double maxPayout = this.config.getPublisherBenefit();
        while (entry != null) {
            if (entry.count > 0) {
                double adversarySuccessProbability = this.getSuccessProbability(entry);
                double informationLoss = this.getEntropyBasedInformationLoss(transformation, entry);
                double realPayout = modelRisk.getExpectedPublisherPayout(informationLoss, adversarySuccessProbability);
                double boundPayout = modelRisk.getExpectedPublisherPayout(informationLoss, 0d);
                real += !entry.isNotOutlier ? (sFactor * entry.count * maxPayout) : 
                                              (gFactor * entry.count * (maxPayout - realPayout));
                bound += gFactor * entry.count * (maxPayout - boundPayout);
            }
            entry = entry.nextOrdered;
        }
        
        // Return
        return super.createInformationLoss(real, bound);
    }

    @Override
    protected InformationLossWithBound<ILSingleDimensional> getInformationLossInternal(Transformation transformation, HashGroupifyEntry entry) {

        // Compute
        double gFactor = super.getGeneralizationFactor();
        double sFactor = super.getSuppressionFactor();
        double adversarySuccessProbability = this.getSuccessProbability(entry);
        double informationLoss = this.getEntropyBasedInformationLoss(transformation, entry);
        double maxPayout = this.config.getPublisherBenefit();
        double realPayout = modelRisk.getExpectedPublisherPayout(informationLoss, adversarySuccessProbability);
        double boundPayout = modelRisk.getExpectedPublisherPayout(informationLoss, 0d);
        double real =  !entry.isNotOutlier ? (sFactor * entry.count * maxPayout) : 
                                             (gFactor * entry.count * (maxPayout - realPayout));
        double bound = gFactor * entry.count * (maxPayout - boundPayout);

        // Return
        return super.createInformationLoss(real, bound);
    }
    
    @Override
    protected ILSingleDimensional getLowerBoundInternal(Transformation transformation) {
        return null;
    }

    @Override
    protected ILSingleDimensional getLowerBoundInternal(Transformation transformation,
                                                        HashGroupify groupify) {


        // Compute
        double bound = 0;
        double gFactor = super.getGeneralizationFactor();
        double maxPayout = this.config.getPublisherBenefit();
        HashGroupifyEntry entry = groupify.getFirstEquivalenceClass();
        while (entry != null) {
            if (entry.count > 0) {
                double informationLoss = this.getEntropyBasedInformationLoss(transformation, entry);
                double boundPayout = modelRisk.getExpectedPublisherPayout(informationLoss, 0d);
                bound += gFactor * entry.count * (maxPayout - boundPayout);
            }
            entry = entry.nextOrdered;
        }
        
        // Return
        return new ILSingleDimensional(bound);
    }

    @Override
    protected void initializeInternal(final DataManager manager,
                                      final DataDefinition definition,
                                      final Data input,
                                      final GeneralizationHierarchy[] hierarchies,
                                      final ARXConfiguration config) {

        super.initializeInternal(manager, definition, input, hierarchies, config);

        // Compute domain shares
        this.shares =  manager.getDomainShares();
        this.config = config.getFinancialConfiguration();
        this.modelRisk = new RiskModelFinancial(this.config);
                
        // Calculate MaxIL
        this.maxIL = 1d;
        for (DomainShare share : shares) {
            this.maxIL *= share.getDomainSize();
        }
        this.maxIL = Math.log10(maxIL);
    }
}
