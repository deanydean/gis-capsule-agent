/*
 * Copyright 2014, Geeks In Space.
 * see http://github.com/geeksinspace/capsule-agent/blob/master/LICENSE
 */
package gis.capsule;

import com.sun.midp.io.Properties;
import com.twitterapime.rest.Credential;
import com.twitterapime.rest.GeoLocation;
import com.twitterapime.rest.TweetER;
import com.twitterapime.rest.UserAccountManager;
import com.twitterapime.search.LimitExceededException;
import com.twitterapime.search.Tweet;
import com.twitterapime.xauth.Token;
import java.io.IOException;

/**
 * Can be used to tweet location information.
 * @author deanydean
 */
public class TwitterBot {
    
    // Parameters to be filled in per account
    // Define a class gis.capsule.TwitterAccount with below params 
    private static final String BOT_SCREENNAME = TwitterAccount.SCREEN_NAME;
    private static final String ACCESS_TOKEN_KEY = TwitterAccount.TOKEN_KEY;
    private static final String ACCESS_TOKEN_SECRET = TwitterAccount.TOKEN_SECRET; 
    private static final String CONSUMER_KEY = TwitterAccount.CONSUMER_KEY;
    private static final String CONSUMER_SECRET = TwitterAccount.CONSUMER_SECRET;

    private TweetER ter = null;
    
    public TwitterBot() throws IOException {
        Credential c = new Credential(BOT_SCREENNAME, CONSUMER_KEY, 
            CONSUMER_SECRET, getAccessToken());
        UserAccountManager m = UserAccountManager.getInstance(c);
        m.setServiceURL(
            UserAccountManager.TWITTER_API_URL_SERVICE_ACCOUNT_VERIFY_CREDENTIALS, 
            "https://api.twitter.com/1/account/verify_credentials.xml");
        
        try{
            if(m.verifyCredential()){
                this.ter = TweetER.getInstance(m);
            }else{
                throw new IOException("Unable to verify credentials");
            }
        }catch(LimitExceededException lee){
            throw new IOException("Twitter limit exceeded: "+lee);
        }
    }
    
    public void tweet(String msg, double lon, double lat) throws IOException {            
        GeoLocation loc = new GeoLocation(
            Double.toString(lat), Double.toString(lon));
        Tweet t = new Tweet(msg, loc);
        
        try{
            t = ter.post(t);
        }catch(LimitExceededException lee){
            throw new IOException("Twitter limit exceeded: "+lee);
        }
    }
    
    public void tweet(String msg) throws IOException {
        Tweet t = new Tweet(msg+" [t"+System.currentTimeMillis()+"]");
        
        try{
            t = ter.post(t);
        }catch(LimitExceededException lee){
            throw new IOException("Twitter limit exceeded: "+lee);
        }
    }
    
    private Token getAccessToken(){
        return new Token(ACCESS_TOKEN_KEY, ACCESS_TOKEN_SECRET);
    }
}
