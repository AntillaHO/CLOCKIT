package de.sleepcompressor.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(8,16,28), SURFACE=Color.rgb(20,32,47);
    private static final int ACCENT=Color.rgb(145,229,211), WHITE=Color.rgb(236,245,249), SUB=Color.rgb(160,177,194);
    private SharedPreferences prefs;
    private SeekBar minBar, maxBar;
    private TextView minValue,maxValue,status,detail;
    private Button power;
    private boolean receiverRegistered;
    private final BroadcastReceiver stateReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){refreshState();}
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences(CompressorService.PREFS,MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildScreen();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},19);
    }

    @Override protected void onStart(){
        super.onStart();
        IntentFilter filter=new IntentFilter(CompressorService.ACTION_STATUS);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(stateReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stateReceiver,filter);
        receiverRegistered=true; refreshState();
    }
    @Override protected void onStop(){
        if(receiverRegistered){unregisterReceiver(stateReceiver);receiverRegistered=false;}
        super.onStop();
    }

    private void buildScreen(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(22),dp(26),dp(22),dp(34));
        scroll.addView(body);setContentView(scroll);
        TextView eyebrow=label("NIGHT AUDIO • V2.0",12,ACCENT,true);eyebrow.setLetterSpacing(.16f);body.addView(eyebrow);
        body.addView(space(11));
        TextView title=label("Sleep\nCompressor",37,WHITE,true);title.setLineSpacing(-dp(2),1f);body.addView(title);
        body.addView(space(8));body.addView(label("Ruhigere Nächte. Weniger plötzliche Lautstärke.",14,SUB,false));
        body.addView(space(22));

        LinearLayout box=panel();body.addView(box);box.addView(label("STATUS",11,ACCENT,true));box.addView(space(8));
        status=label("AUS",21,WHITE,true);box.addView(status);box.addView(space(5));
        detail=label("Starte den Hintergrunddienst zum Testen.",12,SUB,false);box.addView(detail);box.addView(space(18));
        power=button("Kompressor einschalten");
        power.setOnClickListener(v->{
            boolean on=CompressorService.isLive();
            Intent service=new Intent(this,CompressorService.class).setAction(on?CompressorService.ACTION_STOP:CompressorService.ACTION_START);
            if(on)startService(service);else startForegroundService(service);
            detail.setText(on?"Wird beendet …":"Wird gestartet …");
        });
        box.addView(power);
        body.addView(space(20));

        LinearLayout controls=panel();body.addView(controls);controls.addView(label("DEIN LAUTSTÄRKEBEREICH",12,ACCENT,true));
        controls.addView(space(20));
        LinearLayout minHeader=row();controls.addView(minHeader);
        LinearLayout minLabels=column();minHeader.addView(minLabels,new LinearLayout.LayoutParams(0,-2,1f));
        minLabels.addView(label("Mindestlautstärke",17,WHITE,true));
        minLabels.addView(label("Leise Passagen anheben (Zielwert)",12,SUB,false));
        minValue=label("34 %",23,ACCENT,true);minHeader.addView(minValue);
        controls.addView(space(12));minBar=new SeekBar(this);skin(minBar);controls.addView(minBar);
        controls.addView(space(7));
        controls.addView(label("Kein erzwungenes Hochdrehen des Handys in stillen Passagen.",11,SUB,false));
        controls.addView(space(20));
        View divider=new View(this);divider.setBackgroundColor(Color.rgb(49,66,79));
        controls.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));controls.addView(space(21));
        LinearLayout maxHeader=row();controls.addView(maxHeader);
        LinearLayout maxLabels=column();maxHeader.addView(maxLabels,new LinearLayout.LayoutParams(0,-2,1f));
        maxLabels.addView(label("Maximallautstärke",17,WHITE,true));
        maxLabels.addView(label("Android-Medienlimit + DSP-Limiter",12,SUB,false));
        maxValue=label("42 %",23,ACCENT,true);maxHeader.addView(maxValue);
        controls.addView(space(12));maxBar=new SeekBar(this);skin(maxBar);controls.addView(maxBar);
        controls.addView(space(7));controls.addView(label("Die maximale Medienlautstärke wird beim Aktivieren begrenzt.",11,SUB,false));
        int min=clamp(prefs.getInt(CompressorService.KEY_MIN,34));
        int max=clamp(prefs.getInt(CompressorService.KEY_MAX,42));
        minBar.setProgress(Math.min(min,max));maxBar.setProgress(max);
        SeekBar.OnSeekBarChangeListener minListener=listener(true),maxListener=listener(false);
        minBar.setOnSeekBarChangeListener(minListener);maxBar.setOnSeekBarChangeListener(maxListener);refreshValues();
        body.addView(space(16));
        LinearLayout presets=row();body.addView(presets);
        addPreset(presets,"☾  Sleep",34,42);
        addPreset(presets,"◌  Soft",24,60);
        addPreset(presets,"▣  Strong",40,45);
        body.addView(space(21));
        LinearLayout note=panel();body.addView(note);
        note.addView(label("WICHTIG FÜR DIE ANKER P30i",12,ACCENT,true));note.addView(space(7));
        TextView noteText=label("Das Medienlimit greift auf Androids Lautstärkestufen zu. Der zusätzliche Peak-Limiter arbeitet nur, wenn Samsung den globalen Audioeffekt an YouTube/Bluetooth weiterleitet. Die Prozentwerte sind KEINE garantierten dB-Werte an deinen Ohren.",13,WHITE,false);
        noteText.setLineSpacing(dp(3),1f);note.addView(noteText);
    }

    private SeekBar.OnSeekBarChangeListener listener(boolean min){
        return new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int p,boolean fromUser){
                if(fromUser){
                    if(min && p>maxBar.getProgress()) bar.setProgress(maxBar.getProgress());
                    if(!min && p<minBar.getProgress()) bar.setProgress(minBar.getProgress());
                }
                refreshValues();
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){persist();}
        };
    }

    private void addPreset(LinearLayout parent,String name,int min,int max){
        Button b=button(name);b.setTextSize(12);b.setPadding(dp(2),0,dp(2),0);
        b.setBackground(round(Color.rgb(35,55,68),15));b.setTextColor(WHITE);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(49),1f);lp.setMargins(0,0,dp(7),0);parent.addView(b,lp);
        b.setOnClickListener(v->{minBar.setProgress(min);maxBar.setProgress(max);refreshValues();persist();});
    }
    private void persist(){
        int min=Math.min(minBar.getProgress(),maxBar.getProgress()), max=maxBar.getProgress();
        prefs.edit().putInt(CompressorService.KEY_MIN,min).putInt(CompressorService.KEY_MAX,max).apply();
        if(CompressorService.isLive())startService(new Intent(this,CompressorService.class).setAction(CompressorService.ACTION_UPDATE));
    }
    private void refreshValues(){
        if(minValue!=null && maxValue!=null){
            minValue.setText(minBar.getProgress()+" %");maxValue.setText(maxBar.getProgress()+" %");
        }
    }
    private void refreshState(){
        if(status==null)return;
        boolean live=CompressorService.isLive();
        status.setText(live?"AKTIV":"AUS");
        status.setTextColor(live?ACCENT:WHITE);
        detail.setText(live?prefs.getString(CompressorService.KEY_STATUS,"Medienlimit aktiv"):"Kompressor ist ausgeschaltet");
        power.setText(live?"Kompressor ausschalten":"Kompressor einschalten");
        power.setTextColor(live?WHITE:BG);power.setBackground(round(live?Color.rgb(43,63,77):ACCENT,17));
    }

    private void skin(SeekBar s){
        s.setMax(100);s.setProgressTintList(ColorStateList.valueOf(ACCENT));
        s.setThumbTintList(ColorStateList.valueOf(ACCENT));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(61,77,88)));
    }
    private static int clamp(int v){return Math.max(0,Math.min(100,v));}
    private Button button(String value){
        Button b=new Button(this);b.setAllCaps(false);b.setText(value);b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setMinHeight(dp(50));
        b.setBackground(round(ACCENT,17));return b;
    }
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return t;
    }
    private GradientDrawable round(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout panel(){
        LinearLayout l=column();l.setBackground(round(SURFACE,22));l.setPadding(dp(19),dp(19),dp(19),dp(21));return l;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private View space(int v){View p=new View(this);p.setLayoutParams(new LinearLayout.LayoutParams(1,dp(v)));return p;}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
}
