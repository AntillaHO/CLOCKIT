package de.sleepcompressor.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public final class CompressorService extends Service {
    public static final String PREFS="sleep_compressor_preferences";
    public static final String KEY_MIN="min_percent",KEY_MAX="max_percent",KEY_STATUS="status";
    public static final String ACTION_START="de.sleepcompressor.app.START",ACTION_STOP="de.sleepcompressor.app.STOP";
    public static final String ACTION_UPDATE="de.sleepcompressor.app.UPDATE",ACTION_STATUS="de.sleepcompressor.app.STATUS";
    private static final int NOTIFICATION_ID=1905;
    private static final String CHANNEL_ID="night_audio_v2";
    private static volatile boolean live=false;
    private Handler handler;
    private AudioManager audio;
    private DynamicsProcessing dynamics;
    private String dspStatus="DSP noch nicht gestartet";
    private final Runnable ceilingCheck=new Runnable(){
        @Override public void run(){
            if(!live)return;
            enforceVolumeCeiling();
            handler.postDelayed(this,700);
        }
    };
    public static boolean isLive(){return live;}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int id){
        String action=intent==null?ACTION_STOP:intent.getAction();
        if(ACTION_STOP.equals(action)){stopAll();return START_NOT_STICKY;}
        if(!ACTION_START.equals(action)&&!ACTION_UPDATE.equals(action))return START_NOT_STICKY;
        if(!live){
            makeChannel();
            Notification notification=notification();
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID,notification);
            audio=(AudioManager)getSystemService(AUDIO_SERVICE);
            handler=new Handler(Looper.getMainLooper());
            live=true;
            handler.post(ceilingCheck);
        }
        configureEffect();
        enforceVolumeCeiling();
        publish(dspStatus);
        return START_NOT_STICKY;
    }

    private int[] bounds(){
        SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        int minimum=clamp(p.getInt(KEY_MIN,34)),maximum=clamp(p.getInt(KEY_MAX,42));
        return new int[]{Math.min(minimum,maximum),maximum};
    }
    private static int clamp(int n){return Math.max(0,Math.min(100,n));}

    private void enforceVolumeCeiling(){
        if(audio==null||audio.isVolumeFixed())return;
        int maximum=bounds()[1];
        try {
            int maxIndex=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int minIndex=audio.getStreamMinVolume(AudioManager.STREAM_MUSIC);
            int ceiling=Math.max(minIndex,(int)Math.floor(maxIndex*(maximum/100.0)));
            int current=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            // This never raises media volume. Only too-high settings are lowered.
            if(current>ceiling)audio.setStreamVolume(AudioManager.STREAM_MUSIC,ceiling,0);
        }catch(RuntimeException ignored){
            // Android may restrict volume control on some policies; never assume success.
        }
    }

    private void configureEffect(){
        int[] chosen=bounds();
        int minimum=chosen[0], maximum=chosen[1];
        try{
            if(dynamics==null)dynamics=new DynamicsProcessing(100,0,null);
            if(!dynamics.hasControl())throw new IllegalStateException("keine DSP-Kontrolle");
            float gainDb=Math.min(10f,minimum*0.15f);
            float limiterDb=maximum==0?-60f:(float)(20d*Math.log10(maximum/100d));
            float compressionRatio=2.8f+minimum*0.048f;
            dynamics.setInputGainAllChannelsTo(gainDb);
            DynamicsProcessing.Config cfg=dynamics.getConfig();
            int channels=dynamics.getChannelCount(), bands=cfg.getMbcBandCount();
            if(channels<=0||bands<=0)throw new IllegalStateException("kein Multiband-DSP verfügbar");
            for(int c=0;c<channels;c++){
                DynamicsProcessing.Mbc mbc=dynamics.getMbcByChannelIndex(c);
                if(mbc==null||!mbc.isInUse())throw new IllegalStateException("MBC deaktiviert");
                mbc.setEnabled(true);dynamics.setMbcByChannelIndex(c,mbc);
                for(int b=0;b<bands;b++){
                    DynamicsProcessing.MbcBand band=dynamics.getMbcBandByChannelIndex(c,b);
                    band.setEnabled(true);
                    band.setThreshold(-30f);
                    band.setRatio(compressionRatio);
                    band.setKneeWidth(9f);
                    band.setAttackTime(8f);
                    band.setReleaseTime(220f);
                    band.setNoiseGateThreshold(-65f);
                    band.setExpanderRatio(1.3f);
                    band.setPreGain(0f);band.setPostGain(0f);
                    dynamics.setMbcBandByChannelIndex(c,b,band);
                }
                DynamicsProcessing.Limiter lim=dynamics.getLimiterByChannelIndex(c);
                if(lim==null||!lim.isInUse())throw new IllegalStateException("kein Limiter verfügbar");
                lim.setEnabled(true);lim.setLinkGroup(0);
                lim.setThreshold(limiterDb);lim.setRatio(100f);
                lim.setAttackTime(1f);lim.setReleaseTime(110f);
                lim.setPostGain(0f);
                dynamics.setLimiterByChannelIndex(c,lim);
            }
            dynamics.setEnabled(true);
            if(!dynamics.getEnabled())throw new IllegalStateException("DSP nicht aktiviert");
            dspStatus="Medienlimit aktiv · DSP geladen (YouTube-Routing unbestätigt)";
        }catch(Exception|LinkageError e){
            releaseEffect();
            dspStatus="Medienlimit aktiv · DSP nicht verfügbar";
        }
    }

    private void publish(String message){
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY_STATUS,message).apply();
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName()));
    }
    private void makeChannel(){
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null){
            NotificationChannel c=new NotificationChannel(CHANNEL_ID,"Sleep Compressor",NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Medienlautstärke-Limit während des Einschlafens");
            manager.createNotificationChannel(c);
        }
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,CompressorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_moon)
                .setContentTitle("Sleep Compressor aktiv")
                .setContentText("Maximale Android-Medienlautstärke wird begrenzt")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(R.drawable.ic_moon,"Ausschalten",stop).build()).build();
    }
    private void releaseEffect(){
        if(dynamics==null)return;
        try{dynamics.setEnabled(false);}catch(Exception ignored){}
        try{dynamics.release();}catch(Exception ignored){}
        dynamics=null;
    }
    private void stopAll(){
        live=false;
        if(handler!=null)handler.removeCallbacks(ceilingCheck);
        releaseEffect();
        publish("Ausgeschaltet");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onDestroy(){
        live=false;
        if(handler!=null)handler.removeCallbacks(ceilingCheck);
        releaseEffect();
        publish("Dienst beendet");
        super.onDestroy();
    }
}
