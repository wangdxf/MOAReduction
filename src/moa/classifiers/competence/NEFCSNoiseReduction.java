package moa.classifiers.competence;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import jcolibri.cbrcore.CBRCase;
import jcolibri.exception.InitializingException;
import jcolibri.extensions.classification.ClassificationSolution;
import jcolibri.method.maintenance.CaseResult;
import jcolibri.method.maintenance.CompetenceModel;
import jcolibri.method.maintenance.solvesFunctions.CBESolvesFunction;
import jcolibri.method.retrieve.RetrievalResult;
import jcolibri.method.retrieve.NNretrieval.NNScoringMethod;
import jcolibri.method.retrieve.selection.SelectCases;
import jcolibri.method.reuse.classification.KNNClassificationConfig;
import jcolibri.method.reuse.classification.KNNClassificationMethod;
import jcolibri.method.revise.classification.BasicClassificationOracle;
import jcolibri.method.revise.classification.ClassificationOracle;

/**
 * Provides the ability to run the M-BBNR case base editing algorithm 
 * on a case base to eliminate noise.
 * 
 * @author Sergio Ramírez 
 * 01/09/16
 */
public class NEFCSNoiseReduction {
	
	
	protected CompetenceModel model;
	protected List<LinkedHashSet<CBRCase>> relatedSets;
	protected KNNClassificationConfig simConfig;
	protected HashMap<CBRCase, LimitedQueue<Boolean>> accRegister;
	protected List<CBRCase> deactivatedCases;
	protected int l, sizeWindow;
	protected float pmin, pmax, zcoef = 0.5f;
	
	private final static int MIN_PREDICTIONS = 4;
	
	public NEFCSNoiseReduction(KNNClassificationConfig simConfig, int l, int sizeWindow, float pmin, float pmax) {
		// TODO Auto-generated constructor stub
		this.simConfig = simConfig;
		this.l = l;
		this.pmin = pmin;
		this.pmax = pmax;
		this.sizeWindow = sizeWindow;
		deactivatedCases = new LinkedList<CBRCase>();
		accRegister = new HashMap<CBRCase, LimitedQueue<Boolean>>();
		relatedSets = new LinkedList<LinkedHashSet<CBRCase>>();
	}
	
	
	/**
	 * Simulates the M-BBNR editing algorithm, returning the cases
	 * that would be deleted by the algorithm.
	 * @param newCases The group of cases to be considered their addition.
	 * @param caseBase The group of cases on which to perform editing.
	 * @param simConfig The similarity configuration for these cases.
	 * @return the list of cases that would be maintained by the 
	 * NEFCS algorithm.
	 */
	@SuppressWarnings("unchecked")
	public List<CBRCase> applyMaintenance(Collection<CBRCase> newWindow, Collection<CBRCase> oldWindow, Collection<CBRCase> caseBase) {	
		/*
		 * Modified Blame-based Noise Reduction (BBNR) Algorithm
		 * T, Training Set
		 * For each c in T
		 * CSet(c) = Coverage Set of c
		 * LSet(c) = Liability Set of c
		 * End-For
		 *	
		 * TSet = T sorted in descending order of LSet(c) size
		 * c = first case in TSet
		 *	
		 * While |LSet(c)| >0
		 *		TSet = TSet - {c}
		 *		misClassifiedFlag = false
		 *		For each x in CSet(c)
		 *			If CB contains x
		 *				If x cannot be correctly classified by TSet
		 *					misClassifiedFlag = true
		 *					break
		 *				End-If
		 *		End-For
		 *		If misClassifiedFlag = true
		 *			TSet = TSet + {c}
		 *		End-If
		 *		c = next case in TSet
		 * End-While
		 * 
		 * Return TSet
		 */
		LinkedList<CBRCase> newCasestoBeAdded = new LinkedList<CBRCase>();
		
		// New and old examples are united in a single local case base 
		// which will be modified throughout all the process
    	List<CBRCase> localCases = new LinkedList<CBRCase>();
		for(CBRCase c: caseBase){	
			localCases.add(c);
		}
		for(CBRCase c: newWindow) {	
			localCases.add(c);
		}
		
		// Construct the competence model with new and old cases
		updateCompetenceModel(localCases);
		
		// Drift-detection (novel cases won't be removed)
		Collection<CBRCase> novelCases = new LinkedList<CBRCase>();
		if(!newWindow.isEmpty())
			novelCases = driftDetector(oldWindow, newWindow);
		
		// Check if new cases fulfill the conditional M-BBNR rule
		for(CBRCase c: newWindow) {
			Collection<CBRCase> currLiabilitySet = null;
			try {	
				currLiabilitySet = model.getLiabilitySet(c);
			} catch (InitializingException e) {	
				e.printStackTrace();
			}
			// Check if a new case lies outside the identified concept drift competence areas
			if(!novelCases.contains(c)) {
				 if(!(currLiabilitySet.size() > 0 && MBBNRrule(model, c, localCases))) { 
					 // Element is removed inside this function
					 newCasestoBeAdded.add(c);
				 }					 
			} else {
				newCasestoBeAdded.add(c);
			}
		}
		
		// Create the conflicting list
		LinkedList<CBRCase> conflictingList = new LinkedList<CBRCase>();
		for(CBRCase c: caseBase) {
			for(CBRCase nc: newCasestoBeAdded){
				try {	
					Collection<CBRCase> currLiabilitySet = model.getLiabilitySet(c);
					if(currLiabilitySet.contains(nc)){
						conflictingList.add(c);
						break;
					}
				} catch (InitializingException e) {	
					e.printStackTrace();
				}
			}
		}
		
		// Apply M-BBNR on conflicting cases, new cases won't be affected 
		// (first conflicting cases are ordered by its liability set size)
		List<CaseResult> caseLiabilitySetSizes = new LinkedList<CaseResult>();
		for(CBRCase c : conflictingList){	
			Collection<CBRCase> currLiabilitySet = null;
			try {	
				currLiabilitySet = model.getLiabilitySet(c);
			} catch (InitializingException e) {	
				e.printStackTrace();
			}
			int liabilitySetSize = 0;

			if(currLiabilitySet != null) {	
				liabilitySetSize = currLiabilitySet.size();
			}
		
			caseLiabilitySetSizes.add(new CaseResult(c, liabilitySetSize));
			jcolibri.util.ProgressController.step(this.getClass());
		}
		caseLiabilitySetSizes = CaseResult.sortResults(caseLiabilitySetSizes, false);
		
    	for(ListIterator<CaseResult> liabIter = caseLiabilitySetSizes.listIterator(); liabIter.hasNext(); ){	
    		CaseResult highestLiability = liabIter.next();
    		
    		if(highestLiability.getResult() <= 0){	
    			break;    
    		}
    		CBRCase nc = highestLiability.getCase();
    		if(MBBNRrule(model, nc, localCases))
    			newCasestoBeAdded.remove(nc);
    	}
    	
    	// Apply Context Switching to non-removed new cases 
    	for(CBRCase nc: newCasestoBeAdded) {
			contextSwitching(nc, localCases); // local cases can be modified inside this function
		}
   
		updateCompetenceModel(localCases);
		
		return localCases;
	}
	
	
	private boolean MBBNRrule(CompetenceModel sc, CBRCase c, Collection<CBRCase> casebase) {
		
		Collection<CBRCase> covSet = null;
		try {	
			covSet = sc.getCoverageSet(c);
		} catch (InitializingException e) {	
			e.printStackTrace();
		}

		casebase.remove(c);
		
		boolean rule = true;
		for(CBRCase query: covSet) {	
			if(casebase.contains(query)) { // Line 5 (Algorithm 1) paper Li
    			if(!solves(casebase, query)){
    				casebase.add(c);
    				return false;
    			}    			
			}
		}
		return rule;
	}
	
	private boolean solves(Collection<CBRCase> casebase, CBRCase query){

		Collection<RetrievalResult> knn = NNScoringMethod.evaluateSimilarity(casebase, query, simConfig);
		knn = SelectCases.selectTopKRR(knn, simConfig.getK());
		try{	
			KNNClassificationMethod classifier = ((KNNClassificationConfig) simConfig).getClassificationMethod();
			ClassificationSolution predictedSolution = classifier.getPredictedSolution(knn);
			ClassificationOracle oracle = new BasicClassificationOracle();
   			
			return oracle.isCorrectPrediction(predictedSolution, query);
		} catch(ClassCastException cce) {	
			cce.printStackTrace();
			System.exit(0);
		}
		return false;
		
	}
	
	/*private Collection<CBRCase> NN(Collection<CBRCase> casebase, CBRCase query, KNNClassificationConfig simConfig){
		Collection<RetrievalResult> knn = NNScoringMethod.evaluateSimilarity(casebase, query, simConfig);
		return SelectCases.selectTopK(knn, simConfig.getK());		
	}*/

	private void contextSwitching(CBRCase nc, Collection<CBRCase> casebase) {
		Collection<RetrievalResult> knn = NNScoringMethod.evaluateSimilarity(casebase, nc, simConfig);
		knn = SelectCases.selectTopKRR(knn, simConfig.getK());
		int deactIndex = knn.size();
		
		// We also consider deactivated examples in the classification result
		Collection<RetrievalResult> knnd = NNScoringMethod.evaluateSimilarity(deactivatedCases, nc, simConfig);
		knnd = SelectCases.selectTopKRR(knnd, simConfig.getK());
		knn.addAll(knnd);
		
		// Get the prediction result to evaluate examples
		KNNClassificationMethod classifier = ((KNNClassificationConfig)simConfig).getClassificationMethod();
		ClassificationSolution predictedSolution = classifier.getPredictedSolution(knn);
		ClassificationOracle oracle = new BasicClassificationOracle();
		boolean isCorrect = oracle.isCorrectPrediction(predictedSolution, nc);

		// Update l predictions for its neighbors
		int i = 0;
		for(RetrievalResult rr: knn) {
			if(i < deactIndex) {
				updateCS(rr.get_case(), casebase, isCorrect, false);
			} else {
				updateCS(rr.get_case(), casebase, isCorrect, true);
			}
			i++;
		}
	}
	
	private void updateCS(CBRCase c, Collection<CBRCase> casebase, boolean isCorrect, boolean isDeactivated) {
		LimitedQueue<Boolean> register = accRegister.getOrDefault(c, new LimitedQueue<Boolean>(this.l));
		register.add(isCorrect);
		accRegister.put(c, register);
		
		if(register.size() > MIN_PREDICTIONS) {
			double p = 0;
			for(Boolean b: register) {
				if(b) p++;
			}
			p /= l;
			
			float denom = 1 + zcoef * zcoef / l;
			float sqrt = (float) Math.sqrt(p * (1 - p) / l + zcoef * zcoef / (4 * l * l));
			float cimax = (float) ((p + zcoef * zcoef / (2 * l) + zcoef * sqrt) / denom);
			float cimin = (float) ((p + zcoef * zcoef / (2 * l) - zcoef * sqrt) / denom);
			
			if(cimax < pmax && !isDeactivated) {
				casebase.remove(c);
				deactivatedCases.add(c);
			} 
			
			if (cimin > pmin && isDeactivated) {
				casebase.add(c);
				deactivatedCases.remove(c);
			}
		}
	}

	@SuppressWarnings("serial")
	public class LimitedQueue<E> extends LinkedList<E> {
	    private int limit;

	    public LimitedQueue(int limit) {
	        this.limit = limit;
	    }

	    @Override
	    public boolean add(E o) {
	        super.add(o);
	        while (size() > limit) { super.remove(); }
	        return true;
	    }
	}
	

	private List<CBRCase> driftDetector(Collection<CBRCase> owindow, Collection<CBRCase> nwindow) {
		if(!relatedSets.isEmpty()) {
			int rsCount = relatedSets.size();
	        double[] o_weight = new double[rsCount];
	        double[] n_weight = new double[rsCount];
	        
	        int count = 0;
	        List<Integer> temp = new LinkedList<Integer>();
	        for (CBRCase oc: owindow) {
	            count = 0;
	            temp.clear();
	            for (int j = 0; j < rsCount; j++) {
	                if (relatedSets.get(j).contains(oc)) {
	                    count++; // owindow[i] shared by count
	                    temp.add(j);
	                }
	            }

	            for (int j = 0; j < temp.size(); j++){
	                o_weight[temp.get(j)] += 1.0 / (count * owindow.size());
	            }
	        }

	        for (CBRCase nc: nwindow) {
	            count = 0;
	            temp.clear();
	            for (int j = 0; j < rsCount; j++) {
	                if (relatedSets.get(j).contains(nc)) {
	                    count++;
	                    temp.add(j);
	                }
	            }

	            for (int j = 0; j < temp.size(); j++){
	                n_weight[temp.get(j)] += 1.0 / (count * nwindow.size());
	            }
	        }

	        double dis = 0;
	        for (int i = 0; i < o_weight.length; i++) {
	        	if (n_weight[i] > o_weight[i]) {
	                dis += (n_weight[i] - o_weight[i]);
	            }
	        }
	        dis *= 2;

	        double[] vector = new double[rsCount];
	        for (int i = 0; i < rsCount; i++) {
	            vector[i] = n_weight[i] - o_weight[i];
	        }

	        List<Integer> indexes = new LinkedList<Integer>();
	        double identifiedDis = 0, maxdis = 0;
	        int tempIND;
	        while (identifiedDis < 0.1 * dis) {
	            maxdis = 0;
	            tempIND = -1;
	            for (int i = 0; i < rsCount; i++) {
	                if (indexes.contains(i)) {
	                    continue;
	                } else {
	                    if (vector[i] > maxdis) {
	                        maxdis = vector[i];
	                        tempIND = i;
	                    }
	                }
	            }
	            indexes.add(tempIND);
	            identifiedDis = identifiedDis + maxdis;
	        }

	        List<CBRCase> novelCases = new LinkedList<CBRCase>();
	        for (int index: indexes) {
	        	CBRCase agent = relatedSets.get(index).iterator().next(); // It's guaranteed to have at least one element
	            if (!novelCases.contains(agent)) {
	            	if(nwindow.contains(agent))
	                    novelCases.add(agent);
	            }
	        }

	        return novelCases;
		}
		return null;
    }
	

	
	private void updateCompetenceModel(Collection<CBRCase> cases){
		model = new CompetenceModel();
		model.computeCompetenceModel(new CBESolvesFunction(), simConfig, cases);
		relatedSets = computeRelatedSet(cases);
	}
	
	private List<LinkedHashSet<CBRCase>> computeRelatedSet(Collection<CBRCase> cases){
		List<LinkedHashSet<CBRCase>> result = new LinkedList<LinkedHashSet<CBRCase>>();
		for(CBRCase c: cases){
			try {
				LinkedList<CBRCase> cb = new LinkedList<CBRCase>();
				cb.add(c); // Important to be the first
				cb.addAll(model.getCoverageSet(c));
				cb.addAll(model.getReachabilitySet(c));				
			} catch (InitializingException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return result;
	}
}