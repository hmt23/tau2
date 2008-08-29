/**
 * 
 */
package glue;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cqos.WekaClassifierWrapper;
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
	private String classLabel = null;
	private WekaClassifierWrapper wrapper = null;
	
	/**
	 * @param inputs
	 */
	public CQoSClassifierOperation(List<PerformanceResult> inputs, String metric, 
			Set<String> metadataFields, String classLabel) {
		super(inputs);
		this.metric = metric;
		this.metadataFields = metadataFields;
		this.classLabel = classLabel;
	}

 	public List<PerformanceResult> processData() {
		// create a map to store the UNIQUE tuples
		Map<Hashtable<String,String>,PerformanceResult> tuples = 
			new HashMap<Hashtable<String,String>,PerformanceResult>();
		
		// iterate through the inputs
		for (PerformanceResult input : this.inputs) {
			// create a local Hashtable 
			Hashtable<String,String> localMeta = new Hashtable<String,String>();
			// get the input's metadata
			TrialMetadata tmp = new TrialMetadata(input);
			Hashtable<String,String> meta = tmp.getCommonAttributes();
			// create a reduced hashtable
			if (this.metadataFields != null) {
				for (String key : this.metadataFields) {
					localMeta.put(key, meta.get(key));
				}
			// otherwise, if the user didn't specify a set of properties, use them all (?)
			} else {
				for (String key : meta.keySet()) {
					localMeta.put(key, meta.get(key));
				}				
			}
			// put the hashtable in the set: if its performance is "better" than the existing one
			// or if it doesn't exist yet.
			PerformanceResult result = tuples.get(localMeta);
			if (result == null) {
				tuples.put(localMeta,input);
			} else {
				if (input.getInclusive(0, input.getMainEvent(), metric) < 
						result.getInclusive(0, result.getMainEvent(), this.metric)) {
					tuples.put(localMeta,input);					
				}
			}
		}
		
		List<Map<String,String>> trainingData = new ArrayList<Map<String,String>>();

		// ok, we have the set of "optimal" methods for each unique tuple.  Convert them to Instances.
		for (Hashtable<String,String> tmp : tuples.keySet()) {
			Map<String,String> tmpMap = new HashMap<String,String>();
			
			tmpMap.put(this.classLabel, (new TrialMetadata(tuples.get(tmp)).getCommonAttributes().get(classLabel)));
			// set the independent parameters
			for (String metaKey : this.metadataFields) {
				tmpMap.put(metaKey, tmp.get(metaKey));
			}
			trainingData.add(tmpMap);
		}
		
		try {
			this.wrapper = new WekaClassifierWrapper (trainingData, this.classLabel);
			this.wrapper.buildClassifier();
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();			
		}
		
		return null;
	}
	
	public String getClass(Map<String,String> inputFields) {
		return wrapper.getClass(inputFields);
	}

	public double getConfidence() {
		return wrapper.getConfidence();
	}

	public void writeClassifier(String fileName) {
		WekaClassifierWrapper.writeClassifier(fileName, this.wrapper);
	}
	
}
