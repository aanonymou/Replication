package revealdroid.features.intent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.FastVector;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.SparseInstance;
import weka.core.converters.ConverterUtils.DataSource;
import android.content.Intent;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

public class IntentFeaturesAdder {
	public static void main(String[] args) {
		
		JCommander cmd = null;
		IntentFeaturesAdderOptions opts = new IntentFeaturesAdderOptions();
		try {
			cmd = new JCommander(opts);
			cmd.parse(args);
	 	} catch (ParameterException ex) {
	        System.out.println(ex.getMessage());
	        cmd.usage();
	        System.exit(1);
	    }
		
		DataSource source = null;
		Instances data = null;
		try {
			source = new DataSource(opts.arffFile);
			data = source.getDataSet();
			if (data.classIndex() == -1) {
				data.setClassIndex(data.numAttributes()-1);
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if (data.classIndex() == -1) {
			System.err.println(opts.arffFile + "has no class index set.");
		}
		
		List<Attribute> attributes = new ArrayList<Attribute>();
		
		System.out.println("attributes in " + source + ": ");
		for (int attrIndex=0;attrIndex<data.numAttributes();attrIndex++) {
			Attribute attribute = data.attribute(attrIndex);
			System.out.println("\t" + attribute);
			attributes.add(attribute);
		}

		Class<Intent> IntentClass = Intent.class;
		Field[] intentFields = IntentClass.getDeclaredFields();

		System.out.println("action fields of Intent: ");
		for (Field field : intentFields) {
			if (field.toString().contains("android.content.Intent.ACTION_")) {
				String attrName = field.getName(); //.replace("ACTION_", "ACTION____");
				System.out.println(attrName);
				Attribute attribute = new Attribute(attrName);
				
				attributes.add(attribute);
			}
		}
		
		System.out.println("Attributes list with new Intent action attributes:");
		for (Attribute attribute : attributes) {
			System.out.println("\t" + attribute);
		}
		
		int classIndex = -1;
		for (int i=0;i<attributes.size();i++) {
			Attribute attribute = attributes.get(i);
			if (attribute.name().equals("class")) {
				classIndex = i;
			}
		}
		
		if (classIndex == -1) {
			System.err.println("Could not identify class index setting it to last attribute...");
		}
		
		Attribute classAttribute = attributes.remove(classIndex);
		attributes.add(classAttribute);
		//Collections.swap(attributes, classIndex, attributes.size()-1);
		System.out.println("Attributes list with new Intent action attributes and class attribute swapped with last attribute:");
		for (Attribute attribute : attributes) {
			System.out.println("\t" + attribute);
		}
		
		FastVector fvWekaAttributes = new FastVector(attributes.size());
		for (Attribute attribute : attributes) {
			fvWekaAttributes.addElement(attribute);
		}
		
		Map<String,Instance> instancesMap = new LinkedHashMap<String,Instance>();
		System.out.println("Building instances map...");
		for (int i=0;i<data.numInstances();i++) {
			Instance instance = data.instance(i);
			System.out.println("Adding instance for " + instance.stringValue(0));
			instancesMap.put(instance.stringValue(0).trim(), instance);
		}
		
		Instances updatedInstances = new Instances("APK_Features", fvWekaAttributes, data.numInstances());
		for (int i=0;i<updatedInstances.numAttributes();i++) {
			Attribute attribute = updatedInstances.attribute(i);
			if (attribute.name().equals("class")) {
				updatedInstances.setClassIndex(i);
				//updatedInstances.setClass(attribute);
				break;
			}
		}
		/*int updatedInstancesClassIndex = data.numAttributes()-1;
		Attribute updatedInstancesClassattribute = updatedInstances.attribute(updatedInstancesClassIndex);
		updatedInstances.setClass(updatedInstancesClassattribute);
		updatedInstances.setClassIndex(updatedInstancesClassIndex);*/
			
		File actionsDir = new File(opts.actionsDir);
		System.out.println("covert output files:");
		for (File file : actionsDir.listFiles()) {
			if (file.getName().endsWith("_actions.txt")) {
				String apkHash = null;
				if (opts.prefix == null) {
					apkHash = file.getName().substring(file.getName().indexOf("_")+1,file.getName().lastIndexOf("_"));
				}
				else {
					apkHash = file.getName().replaceFirst(opts.prefix, "");
					apkHash = apkHash.substring(0,apkHash.indexOf("_actions.txt"));
				}
				System.out.println("\t" + apkHash);
				Set<String> actions = extractActionsFromFile(file);
				System.out.println("\tandroid Intent actions: ");
				for (String action : actions) {
					System.out.println("\t\t" + action);
				}

				String apkFileName = apkHash + ".apk";
				if (instancesMap.containsKey(apkFileName)) {
					Instance updatedInstance = buildUpdatedInstance(fvWekaAttributes, instancesMap, actions, apkFileName);
					updatedInstances.add(updatedInstance);
				} /*else if (instancesMap.containsKey(apkHash)) {
					Instance updatedInstance = buildUpdatedInstance(fvWekaAttributes, instancesMap, actions, apkHash);
					updatedInstances.add(updatedInstance);
				}*/ else {
					System.out.println("\t no matching apk in original instances for " + apkFileName + ", so skipping");
				}

			}
		}
		
		for (int i=0;i<updatedInstances.numAttributes();i++) {
			Attribute attribute = updatedInstances.attribute(i);
			if (attribute.name().equals("class")) {
				if (i != updatedInstances.classIndex()) {
					throw new RuntimeException("class attribute " + i + " of updatedInstances is different from the set class index " + updatedInstances.classIndex());
				}
			}
		}
		
		for (int i=0;i<updatedInstances.numInstances();i++) {
			Instance instance = updatedInstances.instance(i);
			if (instance.hasMissingValue()) {
				throw new RuntimeException("instance " + i + " of updatedInstances has a missing value");
			}
		}

		for (int i = 0; i < updatedInstances.numInstances(); i++) {
			for (int j = 0; j < data.numInstances(); j++) {
				Instance origInstance = data.instance(j);
				Instance updatedInstance = updatedInstances.instance(i);

				Attribute origInstanceFirstAttr = origInstance.attribute(0);
				Attribute updatedInstanceFirstAttr = updatedInstance.attribute(0);

				if (origInstanceFirstAttr.name().equals(updatedInstanceFirstAttr.name())) {
					if (origInstance.value(0) == updatedInstance.value(0)) {
						updatedInstance.setClassValue(origInstance.classValue());

						if (updatedInstance.hasMissingValue()) {
							throw new RuntimeException("instance " + i + " of updatedInstances has a missing value");
						}
					}

				}
			}

		}
		
		System.out.println("Updated arff:");
		System.out.println(updatedInstances.toString());
		
		System.out.println("Class data of updatedInstances:");
		for (int i=0;i<updatedInstances.numInstances();i++) {
			Instance instance = updatedInstances.instance(i);
			System.out.println("\tindex: " + instance.classIndex());
			System.out.println("\tvalue: " + instance.classValue());
		}
		
		try {
			String baseName = opts.arffFile.substring(0,opts.arffFile.lastIndexOf("."));
			
			FileWriter writer = new FileWriter(baseName + "_intent_actions.arff");
			writer.write(updatedInstances.toString());
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static Set<String> extractActionsFromFile(File file) {
		Set<String> actions = new LinkedHashSet<String>();
		try (BufferedReader reader = Files.newBufferedReader(file.toPath(), Charset.defaultCharset())) {
		    String line = null;
		    while ((line = reader.readLine()) != null) {
		        //System.out.println(line);
		    	if (line.contains("android.intent.action")) {
		    		actions.add(line.trim());
		    	}
		    }
		} catch (IOException x) {
		    System.err.format("IOException: %s%n", x);
		}
		return actions;
	}

	public static Instance buildUpdatedInstance(FastVector fvWekaAttributes, Map<String, Instance> instancesMap, Set<String> actions, String apkFileName) {
		Instance origInstance = instancesMap.get(apkFileName);
		System.out.println("\t original instance: " + origInstance.stringValue(0));
		
		Instance updatedInstance = new DenseInstance(fvWekaAttributes.size());
		int attIndex = 0;
		for (attIndex=0;attIndex<origInstance.numAttributes();attIndex++) {
			Attribute origAttribute = origInstance.attribute(attIndex);
			if (origAttribute.type() == Attribute.NUMERIC) {
				updatedInstance.setValue(origAttribute, origInstance.value(attIndex));
			}
			else if (origAttribute.type() == Attribute.STRING) {
				updatedInstance.setValue(origAttribute, origInstance.stringValue(attIndex));
			}
			else if (origAttribute.type() == Attribute.NOMINAL) {
				updatedInstance.setValue(origAttribute, origInstance.value(attIndex));
			}
			else {
				throw new RuntimeException("Unhandled attribute type in original instance at index " + attIndex);
			}
		}
		attIndex--;
		while (attIndex < fvWekaAttributes.size()) {
			Attribute newAttribute = (Attribute) fvWekaAttributes.elementAt(attIndex);
			String actionConverted = newAttribute.name().replace("ACTION_","android.intent.action.");
			if (actions.contains(actionConverted.trim())) {
				updatedInstance.setValue(newAttribute, 1);
			}
			else {
				updatedInstance.setValue(newAttribute, 0);
			}
			attIndex++;
		}
		return updatedInstance;
	}
}
