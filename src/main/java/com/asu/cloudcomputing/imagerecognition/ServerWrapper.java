package com.asu.cloudcomputing.imagerecognition;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.codec.binary.Base64;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.asu.cloudcomputing.util.StringConstantUtil;

public class ServerWrapper {
	
	static AmazonEC2 ec2;
	
	static void createInstance(String tier, String tierClass, Integer appInstCount) {
		 String instanceName = tier+"-instance-"+appInstCount;
		 String userData =  "#!/bin/bash\n" + 
			 		"mkdir /home/ubuntu/.aws\n" + 
			 		"printf \"[default]\\noutput = json\\nregion = "+StringConstantUtil.REGION+"\\n\" > home/ubuntu/.aws/config\n" + 
			 		"printf \"[default]\\naws_secret_access_key = "+StringConstantUtil.ACCESS_KEY+"\\naws_access_key_id = "+StringConstantUtil.ACCESS_KEY_ID+"\\n\" > home/ubuntu/.aws/credentials\n" + 
			 		"su ubuntu -c \"java -cp /home/ubuntu/image-recognition.jar com.asu.cloudcomputing.imagerecognition."+tierClass+" "+instanceName+"\"";
		 RunInstancesRequest runInstance = new RunInstancesRequest();		 
		 String startupScriptString = Base64.encodeBase64String(userData.getBytes());	
		 runInstance.withImageId(StringConstantUtil.AMI).withInstanceType(StringConstantUtil.INSTANCE_TYPE).
			withMinCount(1).withMaxCount(1).withKeyName(StringConstantUtil.PUBLIC_KEY_NAME).withSecurityGroups("default").withUserData(startupScriptString);
		 RunInstancesResult result = ec2.runInstances(runInstance);	 
		 // set instance name
		 List<Tag> tags = new ArrayList<Tag>();		 
		 Tag tag = new Tag();
		 tag.setKey("Name");
		 tag.setValue(instanceName);
		 tags.add(tag);
		 CreateTagsRequest ctr = new CreateTagsRequest();
		 ctr.withTags(tags);
		 List<Instance> resultInstanceList = result.getReservation().getInstances(); 
		 ctr.withResources(resultInstanceList.get(0).getInstanceId());
		 ec2.createTags(ctr);
		 System.out.println("Tag Created");
	 }
	
	public static void main(String[] args) throws Exception {
		ec2 = AmazonEC2ClientBuilder.standard().withRegion(StringConstantUtil.REGION).build();
		createInstance("web","WebTier",0);
		createInstance("app","AppTier",0);
	}
}
