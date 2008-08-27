/**
 * 
 */
package glue;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import edu.uoregon.tau.perfdmf.Trial;
import weka.core.*;
import weka.classifiers.Classifier;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.StringToNominal;

/**
 * @author khuck
 *
 */
public class CQoSClassifierOperation extends AbstractPerformanceOperation {

	private String metric = "Time";
	private Set<String> metadataFields = null;
	private String methodName = null;
	private Classifier cls = null;
	private Attribute classAttr = null;
	private FastVector atts = null;
	private Attribute[] attArray = null;
	private double confidence = 0.0;
	private String className = null;
	
	/**
	 * @param inputs
	 */
	public CQoSClassifierOperation(List<PerformanceResult> inputs, String metric, Set<String> metadataFields, String methodName) {
		super(inputs);
		this.metric = metric;
		this.metadataFields = metadataFields;
		this.methodName = methodName;
	}

	/* (non-Javadoc)
	 * @see glue.PerformanceAnalysisOperation#processData()
	 */
	public List<PerformanceResult> processData() {
		// get the unique tuples
		Map<Hashtable<String,String>,PerformanceResult> tuples = new HashMap<Hashtable<String,String>,PerformanceResult>();
		
		for (PerformanceResult input : this.inputs) {
			// create a local Hashtable 
			Hashtable<String,String> localMeta = new Hashtable<String,String>();
			// get the input's metadata
			TrialMetadata tmp = new TrialMetadata(input);
			Hashtable<String,String> meta = tmp.getCommonAttributes();
			// create a reduced hashtable
			for (String key : this.metadataFields) {
				localMeta.put(key, meta.get(key));
			}
			// put the hashtable in the set, if its performance is "better" than the existing one, if it exists.
			PerformanceResult result = tuples.get(localMeta);
			if (result == null) {
				tuples.put(localMeta,input);
			} else {
				if (input.getInclusive(0, input.getMainEvent(), metric) < result.getInclusive(0, result.getMainEvent(), this.metric)) {
					tuples.put(localMeta,input);					
				}
			}
		}
		
		// some debugging output...
		
/*		System.out.println(tuples.size());
		for (Hashtable<String,String> tmp : tuples.keySet()) {
			System.out.print(tmp.toString() + ": " + tuples.get(tmp).getInclusive(0, tuples.get(tmp).getMainEvent(), metric)/1000000);
			System.out.println(": " + (new TrialMetadata(tuples.get(tmp)).getCommonAttributes().get(methodName)));
		}
*/		
		
		// TODO: for each input parameter, check if it is numeric or categorical.  For now, assume numeric.
		
		// build the classifier!
		// set the classes to null - we will convert them to nominal with a filter later.
        FastVector classes = null;
        this.atts = new FastVector();
        attArray = new Attribute[this.metadataFields.size()+1];
		classAttr = new Attribute(this.methodName, classes);
		attArray[0] = classAttr;
		this.atts.addElement(classAttr);
        int i = 1;
		for (String key : this.metadataFields) {
			attArray[i] = new Attribute(key);
			this.atts.addElement(attArray[i]);
			i++;
		}
		
        Instances train = new Instances("train", this.atts, tuples.size());
        train.setClass(attArray[0]);
        
		for (Hashtable<String,String> tmp : tuples.keySet()) {
			Instance inst = new Instance(tmp.size()+1);
			inst.setValue(attArray[0], (new TrialMetadata(tuples.get(tmp)).getCommonAttributes().get(methodName)));
			for (i = 1 ; i < attArray.length ; i++) {
				inst.setValue(attArray[i], Double.parseDouble(tmp.get(attArray[i].name())));
			}
			train.add(inst);
		}

		try {
			train.setClassIndex(0);
	        StringToNominal filter = new StringToNominal();
	        filter.setAttributeIndex("1");
	        filter.setInputFormat(train);
	        train = Filter.useFilter(train,filter);

//			this.cls = Classifier.forName("weka.classifiers.functions.SMO", null);
//			this.cls = Classifier.forName("weka.classifiers.bayes.NaiveBayes", null);
	        this.cls = Classifier.forName("weka.classifiers.functions.MultilayerPerceptron", null);
			this.cls.buildClassifier(train);
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}

		return null;
	}

	public Classifier getClassifier() {
		return cls;
	}

	public String getClass(double[] distribution) {
		int i = 0;
		for (int j = 1 ; j < classAttr.numValues(); j++) {
			if (distribution[j] > distribution[i]) {
				i = j;
			}
		}
		this.confidence = distribution[i];
		this.className = classAttr.value(i);
		return className;
	}
	
	public String getClass(Map<String,String> inputFields) {
		String className = null;
		
        Instances test = new Instances("test", atts, 3);
    	Instance inst = new Instance(attArray.length);
		for (int i = 1 ; i < attArray.length ; i++) {
			inst.setValue(attArray[i], Double.parseDouble(inputFields.get(attArray[i].name())));
		}
		test.add(inst);
		test.setClassIndex(0);
        
		inst = test.firstInstance();
        try {
//	        System.out.print(" [");
	        double[] dist = cls.distributionForInstance(inst);
//	        for (int i = 0 ; i < dist.length ; i++) {
//	            System.out.print (dist[i] + ",");
//	        }
//	        System.out.print("] ");
			className = getClass(dist);
        } catch (Exception e) {
        	System.err.println(e.getMessage());
        	e.printStackTrace();
        }
		
		return className;
	}

	public double getConfidence() {
		return confidence;
	}

	public void setConfidence(double confidence) {
		this.confidence = confidence;
	}

	public String getClassName() {
		return className;
	}

	public void setClassName(String className) {
		this.className = className;
	}
}
