package com.homenet.agent;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProbeActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText host, user, pass;
    private TextView status, output;
    private Button button;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView s = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(30));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        s.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this); title.setText("HomeNet Agent v0.1.2"); title.setTextSize(25); root.addView(title);
        TextView sub = new TextView(this); sub.setText("فحص اتصال TP-Link WR840N وقراءة Traffic Statistics"); sub.setTextSize(15); sub.setPadding(0,0,0,dp(14)); root.addView(sub);
        host = field("عنوان الراوتر", "192.168.0.1"); root.addView(host);
        user = field("اسم المستخدم", "admin"); root.addView(user);
        pass = field("كلمة مرور الراوتر", ""); pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(pass);
        button = new Button(this); button.setText("فحص الاتصال وقراءة الأجهزة"); root.addView(button);
        status = new TextView(this); status.setText("جاهز. تأكد أن الهاتف على نفس Wi‑Fi."); status.setTextSize(15); status.setPadding(0,dp(12),0,dp(12)); root.addView(status);
        output = new TextView(this); output.setTextSize(14); root.addView(output);
        button.setOnClickListener(v -> run());
        setContentView(s);
    }

    private EditText field(String hint, String value) { EditText e=new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); return e; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }

    private void run() {
        final String h=host.getText().toString().trim(), u=user.getText().toString().trim(), p=pass.getText().toString();
        button.setEnabled(false); status.setText("جاري الفحص..."); output.setText("");
        executor.submit(() -> {
            try {
                Result r = new Client(h).read(u,p);
                ui.post(() -> show(r));
            } catch(Exception e) {
                ui.post(() -> { status.setText("فشل الفحص"); output.setText(e.getMessage()==null?e.toString():e.getMessage()); button.setEnabled(true); });
            }
        });
    }

    private void show(Result r){
        button.setEnabled(true);
        if(r.devices.isEmpty()){
            status.setText("وصلنا للراوتر، لكن لم نستخرج صفوف الاستهلاك بعد.");
            output.setText(r.diag);
            return;
        }
        status.setText("تم الاتصال وقراءة " + r.devices.size() + " جهاز ✅");
        StringBuilder sb=new StringBuilder();
        for(Device d:r.devices){
            sb.append("\nIP: ").append(d.ip).append("\nMAC: ").append(d.mac)
              .append("\nTotal: ").append(human(d.totalBytes))
              .append("   Current: ").append(human(d.currentBytes)).append("\n");
        }
        sb.append("\n").append(r.diag);
        output.setText(sb.toString());
    }

    private static String human(long b){ double v=Math.max(0,b); if(v>=1e9)return String.format(Locale.US,"%.3f GB",v/1e9); if(v>=1e6)return String.format(Locale.US,"%.2f MB",v/1e6); if(v>=1e3)return String.format(Locale.US,"%.2f KB",v/1e3); return ((long)v)+" B"; }

    static class Device { String ip,mac; long totalBytes,currentBytes; Device(String i,String m,long t,long c){ip=i;mac=m;totalBytes=t;currentBytes=c;} }
    static class Result { List<Device> devices; String diag; Result(List<Device>d,String x){devices=d;diag=x;} }

    static class Client {
        private final String base;
        private static final Pattern TOKEN=Pattern.compile("(?:/|^)([A-Za-z0-9]{16})/userRpm/", Pattern.CASE_INSENSITIVE);
        private static final Pattern ROW=Pattern.compile("\\d+\\s*,\\s*\"(\\d{1,3}(?:\\.\\d{1,3}){3})\"\\s*,\\s*\"([0-9A-Fa-f:-]{11,20})\"\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)",Pattern.MULTILINE);
        Client(String h){ h=h.replace("http://","").replace("https://",""); while(h.endsWith("/"))h=h.substring(0,h.length()-1); base="http://"+h; }

        Result read(String user,String pass) throws Exception {
            String plain=Base64.encodeToString((user+":"+pass).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
            String md=Base64.encodeToString((user+":"+md5(pass)).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP);
            List<Auth> auths=new ArrayList<>();
            auths.add(new Auth("cookie-md5-basic","Authorization=Basic%20"+md+"; ChgPwdSubTag=",null));
            auths.add(new Auth("cookie-md5","Authorization="+md+"; ChgPwdSubTag=",null));
            auths.add(new Auth("cookie-plain-basic","Authorization=Basic%20"+plain+"; ChgPwdSubTag=",null));
            auths.add(new Auth("cookie-plain","Authorization="+plain+"; ChgPwdSubTag=",null));
            auths.add(new Auth("http-basic",null,"Basic "+plain));
            auths.add(new Auth("http-basic-md5",null,"Basic "+md));

            StringBuilder diag=new StringBuilder();
            for(Auth a:auths){
                try{
                    Resp login=get(base+"/userRpm/LoginRpm.htm?Save=Save",a,base+"/");
                    diag.append(a.name).append(" login=").append(login.code);
                    if(!login.location.isEmpty()) diag.append(" loc=").append(shorten(login.location));
                    diag.append("\n");
                    String token=findToken(login.location+"\n"+login.body);
                    List<String> paths=new ArrayList<>();
                    if(token!=null) paths.add("/"+token+"/userRpm/SystemStatisticRpm.htm?interval=10&sortType=1&Num_per_page=100&Goto_page=1");
                    paths.add("/userRpm/SystemStatisticRpm.htm?interval=10&sortType=1&Num_per_page=100&Goto_page=1");
                    for(String path:paths){
                        Resp r=get(base+path,a, token==null?base+"/":base+"/"+token+"/userRpm/Index.htm");
                        List<Device> d=parse(r.body);
                        diag.append("  stats=").append(r.code).append(" rows=").append(d.size()).append(" path=").append(path).append("\n");
                        if(!d.isEmpty()) return new Result(d,diag.toString());
                    }
                }catch(Exception e){ diag.append(a.name).append(" error=").append(shorten(e.getMessage())).append("\n"); }
            }
            return new Result(new ArrayList<>(),diag.toString());
        }

        private Resp get(String url,Auth a,String ref) throws Exception{
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(6000); c.setInstanceFollowRedirects(false); c.setRequestMethod("GET");
            if(a.cookie!=null)c.setRequestProperty("Cookie",a.cookie);
            if(a.authorization!=null)c.setRequestProperty("Authorization",a.authorization);
            c.setRequestProperty("Referer",ref); c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept","text/html,application/xhtml+xml,*/*");
            int code=c.getResponseCode(); String loc=c.getHeaderField("Location"); InputStream in=code>=400?c.getErrorStream():c.getInputStream(); String body=readAll(in); c.disconnect(); return new Resp(code,body,loc==null?"":loc);
        }
        private static List<Device> parse(String body){ List<Device> out=new ArrayList<>(); if(body==null)return out; Matcher m=ROW.matcher(body); while(m.find()) out.add(new Device(m.group(1),m.group(2),pos(m.group(4)),pos(m.group(6)))); return out; }
        private static long pos(String s){ try{return Math.max(0,Long.parseLong(s));}catch(Exception e){return 0;} }
        private static String findToken(String x){ Matcher m=TOKEN.matcher(x==null?"":x); return m.find()?m.group(1):null; }
        private static String md5(String s)throws Exception{ MessageDigest md=MessageDigest.getInstance("MD5"); byte[] d=md.digest(s.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte x:d)b.append(String.format(Locale.US,"%02x",x&255)); return b.toString(); }
        private static String readAll(InputStream in)throws Exception{ if(in==null)return""; StringBuilder s=new StringBuilder(); try(BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.ISO_8859_1))){String l;while((l=b.readLine())!=null)s.append(l).append('\n');}return s.toString(); }
        private static String shorten(String s){ if(s==null)return""; s=s.replace('\n',' ').replace('\r',' '); return s.length()>100?s.substring(0,100):s; }
        static class Auth{String name,cookie,authorization;Auth(String n,String c,String a){name=n;cookie=c;authorization=a;}}
        static class Resp{int code;String body,location;Resp(int c,String b,String l){code=c;body=b;location=l;}}
    }
}
