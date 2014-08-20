/*
 * Copyright 2014, Geeks In Space.
 * see http://github.com/geeksinspace/capsule-agent/blob/master/LICENSE
 */
package gis.capsule;

import java.io.IOException;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;

import javax.microedition.location.Location;
import javax.microedition.location.LocationException;
import javax.microedition.location.QualifiedCoordinates;
import net.sf.microlog.core.Logger;
import net.sf.microlog.core.LoggerFactory;

import net.sourceforge.mtcs.location.Locator;
import net.sourceforge.mtcs.net.WebserviceConnection;
import net.sourceforge.mtcs.net.rest.JSONRESTRequest;

/**
 * Handles the runtime of the capsule agent.
 * @author deanydean
 */
public class Worker implements Runnable {

    public static final String NO_CONNECTION = "No Connection";
    public static final String NO_LOCATION = "No Location";

    public static final String PHASE_TRACKING = "Tracking";
    
    public static final String TYPE_LOCATION = "location";
    public static final String TYPE_ERROR = "error"; 
    
    public static final String DATA_LAT = "lat";
    public static final String DATA_LONG = "long";
    public static final String DATA_ALT = "alt";
    public static final String DATA_VACC = "vacc";
    public static final String DATA_HACC = "hacc";
    public static final String DATA_SPD = "spd";
    public static final String DATA_MTHD = "mthd";
    public static final String DATA_COURSE = "course";
    public static final String DATA_TS = "time";
    public static final String DATA_BAT = "bat";
    public static final String DATA_SIG = "sig";
    
    public static final String NOKIA_NETSIG = "com.nokia.mid.networksignal";
    public static final String NOKIA_BATLEV = "com.nokia.mid.batterylevel";
    
    private static final long TWEET_INTERVAL = 5*60*1000; // 5 mins

    private static final Logger LOG = LoggerFactory.getLogger(Worker.class);
    
    private final CapsuleAgent parent;
    private final int interval;
    private boolean running = false;
    private Locator locator = null;
    //private Location location = null;
    private String missionID = "TESTING";
    
    private TwitterBot twitterBot = null;
    private long lastTweetTime = 0L;
    
    private WebserviceConnection connection;
    
    public Worker(CapsuleAgent parent, int interval){
        this.parent = parent;
        this.interval = interval;
        this.missionID = this.parent.getMissionID().getString();
        
        this.connection = new WebserviceConnection();
        
        try{
            this.twitterBot = new TwitterBot();
        }catch(Exception e){
            LOG.error("Failed to connect to twitter", e);
            this.parent.updateConnStatus("Failed to connect to twitter");
        }
    }
    
    public void run(){
        this.running = true;
        
        try{
            // Init locator
            this.locator = new Locator(this.interval);
        }catch(LocationException le){
            // Report error
            LOG.error("Failed to init locator", le);
            this.parent.updateTrackerStatus("Failed to init locator");
            return;
        }
        
        // Set initial tracking phase
        this.parent.getPhaseString().setText(PHASE_TRACKING);                
        
        while(this.running){
            try{
                // Get the current data
                Hashtable data = getCurrentData();

                // Module Software Logic
                
                // 1. Report the location.
                publishData(data);

                // 2. Log data
                logData(data);

                // 3. Tweet (if we are allowed to)
                tweet("Tracking update", data);
            }catch(Throwable t){
                LOG.error("Tracking error", t);
            }

            // Wait until next run
            try{
                Thread.sleep(this.interval*1000);
            }catch(InterruptedException ie){
                // TODO: Review possible spinning?
                LOG.error("Interrupted during "+this.interval+"sec wait", ie);
            }
        }
    }
    
    public void stop(){
        LOG.info("Stopping worker");
        if(this.running) this.running = false;
    }
    
    public boolean isRunning(){
        return this.running;
    }
    
    /**
     * Get the current data for this iteration.
     * @return the current data
     */
    public Hashtable getCurrentData(){
        Hashtable data = new Hashtable();
        
        try{
            Location location = this.locator.getCurrentLocation();
            this.parent.updateTrackerStatus("Location updated");
            
            if(location != null){
                QualifiedCoordinates coords = 
                    location.getQualifiedCoordinates();
                
                data.put(DATA_LAT, Double.toString(coords.getLatitude()));
                data.put(DATA_LONG, Double.toString(coords.getLongitude()));
                data.put(DATA_ALT, Double.toString(coords.getAltitude()));
                data.put(DATA_VACC, 
                    Double.toString(coords.getVerticalAccuracy()));
                data.put(DATA_HACC, 
                    Double.toString(coords.getHorizontalAccuracy()));
                data.put(DATA_SPD, Float.toString(location.getSpeed()));
                data.put(DATA_MTHD, 
                    Integer.toString(location.getLocationMethod()));
                data.put(DATA_COURSE, Float.toString(location.getCourse()));
                data.put(DATA_TS, Long.toString(location.getTimestamp()));
                
                // Update UI
                this.parent.getLatitudeString().setText(
                    (String) data.get(DATA_LAT));
                this.parent.getLongitudeString().setText(
                    (String) data.get(DATA_LONG));
                this.parent.getAltitudeString().setText(
                    (String) data.get(DATA_ALT));
            }
        }catch(LocationException le){
            LOG.error("Failed to get location", le);
            this.parent.updateTrackerStatus("Failed to get location");
        }
        
        if(System.getProperty(NOKIA_NETSIG) != null)
            data.put(DATA_SIG, System.getProperty(NOKIA_NETSIG));
        if(System.getProperty(NOKIA_BATLEV) != null)
            data.put(DATA_BAT, System.getProperty(NOKIA_BATLEV));
        
        LOG.info("Got current data");
        return data;
    }
    
    /**
     * Publish the provided data to mission control.
     * @param data the data to publish
     */
    public void publishData(Hashtable data){
        this.parent.updateConnStatus("Publishing location");
        
        // Build the request
        JSONRESTRequest request = new JSONRESTRequest();
        request.setMethod("POST");
        
        request.put("ts", Long.toString(new Date().getTime()));
        request.put("type", TYPE_LOCATION);
        
        Enumeration keys = data.keys();
        while(keys.hasMoreElements()){
            String key = (String) keys.nextElement();
            request.put(key, data.get(key));
        }
        
        // Try primary url
        request.setUrl("http://"+this.parent.getMcUrlAInput().getString());
        try{
            this.connection.send(request);
        }catch(Throwable ta){
            this.parent.updateConnStatus(NO_CONNECTION+" URL A, trying URL B");
            LOG.error("Failed to connect to URL A, trying URL B", ta);
            tweet("Lost contact with mission control A", data);
            
            // Something went wrong, try url B
            request.setUrl("http://"+this.parent.getMcUrlBInput().getString());
            try{
                this.connection.send(request);
            }catch(Throwable tb){
                // No comms links, report
                this.parent.updateConnStatus(NO_CONNECTION+": "+tb);
                LOG.error("Failed to connect to URL B: "+tb);
                tweet("Lost contact mission control", data);
                LOG.error("Lost contact with mission control");
            }
        }
    }
    
    public void logData(Hashtable data){
        this.parent.updateTrackerStatus("Logging data");
        
        LOG.info("Current location [ lat="+data.get(DATA_LAT)+
            " long="+data.get(DATA_LONG)+
            " alt="+data.get(DATA_ALT)+" ]");
        LOG.info("Current accurracy = [ h="+data.get(DATA_HACC)+
            " v="+data.get(DATA_VACC)+" ]");
        LOG.info("Current speed = "+data.get(DATA_SPD)+"m/s");
        LOG.info("Current battery level = "+data.get(DATA_BAT));
        LOG.info("Current signal strength = "+data.get(DATA_SIG));
    }
    
    public void tweet(String msg, Hashtable data){
        if(System.currentTimeMillis() < lastTweetTime+TWEET_INTERVAL){
            LOG.info("Not tweeting. Last tweet (@"+lastTweetTime+") was "+ 
                "less than "+TWEET_INTERVAL+" ago.");
            return;
        }
            
        if(twitterBot == null){
            try{
                twitterBot = new TwitterBot();
            }catch(IOException ioe){
                this.parent.updateConnStatus("Failed to connect to twitter");
                LOG.error("Failed to connect to twitter", ioe);
            }
        }
        
        if(twitterBot != null){
            try{
                if(data.get(DATA_ALT) != null && data.get(DATA_SPD) != null){
                    twitterBot.tweet(
                        this.missionID+": "+msg+". alt="+data.get(DATA_ALT)+
                        "m speed="+data.get(DATA_SPD)+"m/s");
                    
                }else{
                    twitterBot.tweet(this.missionID+": "+msg);
                }
                
                lastTweetTime = System.currentTimeMillis();
            }catch(IOException ioe){
                this.parent.updateConnStatus("Tweet failed");
                LOG.error("Failed to tweet", ioe);
            }
        }
    }
}
