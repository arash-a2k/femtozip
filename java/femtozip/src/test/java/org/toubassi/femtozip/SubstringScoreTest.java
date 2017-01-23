package org.toubassi.femtozip;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.swing.plaf.basic.BasicInternalFrameTitlePane.SystemMenuBar;

import org.junit.Assert;
import org.junit.Test;
import org.toubassi.femtozip.models.CompressionModelBase;
import org.toubassi.femtozip.models.CompressionModelVariant;

/**
 * A Simple API example, packaged as a unit test
 */
public class SubstringScoreTest {

    @Test
    public void example() throws IOException {
        String commonOccured = "arash";
    	ArrayDocumentList trainingDocs = new ArrayDocumentList("http://espn.de", "http://popsugar.de",
                "http://google.de", "http://yahoo.de", "http://www.linkedin.com", "http://www.facebook.com",
                "http:www.stanford.edu", commonOccured + "!",
                commonOccured + ">", commonOccured + "_",
                commonOccured + ")");
        
        Object[] modelAndSubscores = CompressionModelBase.buildModelWithSubscores(CompressionModelVariant.FemtoZip, trainingDocs, 1024);
        CompressionModel model = (CompressionModel)modelAndSubscores[0];
        Map<byte[], Integer> subscores = (Map<byte[], Integer>) modelAndSubscores [1];
        Map<String, Integer> substringScores = new HashMap<String, Integer>();
        
          for (byte[] x: subscores.keySet()){
        	System.out.println(new String (x, "UTF-8") + " " +  subscores.get(x));
        	substringScores.put(new String (x, "UTF-8"), subscores.get(x));
        }
        
        Assert.assertFalse(subscores.isEmpty());
        Assert.assertTrue(substringScores.containsKey(commonOccured));
        
        
    }
}
