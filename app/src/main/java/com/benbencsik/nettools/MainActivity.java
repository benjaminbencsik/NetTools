package com.benbencsik.nettools;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.net.InetAddress;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(11,17,27), CARD = Color.rgb(20,30,44),
            FG = Color.rgb(235,242,250), MUTED = Color.rgb(151,169,190),
            ACCENT = Color.rgb(99,230,194), LINE = Color.rgb(40,57,77);
    private static final String[] TOOLS = {"Ping", "DNS lookup", "TCP ports", "HTTP headers", "TLS certificate", "Subnet calculator", "Network details"};
    private static final String[] DESCRIPTIONS = {"Latency & packet loss", "Resolve IPv4 & IPv6 addresses", "Check a host's listening ports", "Status, redirects & headers", "Verify HTTPS & expiry", "IPv4 ranges, masks & hosts", "Connection, local IP & DNS"};
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Future<?> current;
    private int generation = 0, selected = -1;
    private LinearLayout root, body;
    private EditText target, extra;
    private Button run, stop;
    private TextView result, status;
    private ProgressBar progress;
    private String lastResult = "", currentTarget = "", currentPorts = "22,80,443,3389,8080";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            selected = saved.getInt("selected", -1);
            lastResult = saved.getString("result", "");
            currentTarget = saved.getString("target", "");
            currentPorts = saved.getString("ports", currentPorts);
        }
        show(selected < 0 ? -1 : selected);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        captureInputs();
        out.putInt("selected", selected);
        out.putString("result", lastResult);
        out.putString("target", currentTarget);
        out.putString("ports", currentPorts);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        cancel();
        workers.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (selected >= 0) { cancel(); lastResult = ""; currentTarget = ""; show(-1); }
        else super.onBackPressed();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), LINE); return background;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setLineSpacing(dp(3), 1); return view;
    }
    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2);
        params.topMargin = dp(top); parent.addView(view, params);
    }
    private LinearLayout panel() {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18),dp(18),dp(18),dp(18)); layout.setBackground(shape(CARD,18)); return layout;
    }
    private Button button(String title, boolean primary) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false);
        button.setTextSize(14); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? BG : FG); button.setBackground(shape(primary ? ACCENT : CARD,12));
        button.setMinHeight(dp(48)); return button;
    }

    private void show(int tool) {
        selected = tool; target = null; extra = null; result = null;
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        // Respect Android 15 edge-to-edge insets, including landscape and gesture navigation.
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22),dp(16),dp(22),dp(12));
        TextView brand = text(tool < 0 ? "NetTools" : "‹  " + TOOLS[tool], 24, FG, true);
        header.addView(brand, new LinearLayout.LayoutParams(0,-2,1));
        if (tool >= 0) { brand.setContentDescription("Back to toolkit"); brand.setOnClickListener(v -> onBackPressed()); }
        else {
            TextView label = text("ON DEVICE",10,ACCENT,true); label.setPadding(dp(10),dp(7),dp(10),dp(7));
            label.setBackground(shape(CARD,12)); header.addView(label);
        }
        root.addView(header);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(22),dp(6),dp(22),dp(26));
        scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if (tool < 0) { home(); navigation(); } else toolScreen(tool);
    }

    private void home() {
        add(body,text("Your network.\nA little more clarity.",30,FG,true),8);
        add(body,text("Practical diagnostics, right in your pocket.",15,MUTED,false),8);
        LinearLayout hero = panel();
        String connection = connectionLabel();
        add(hero,text("CURRENT CONNECTION",10,ACCENT,true),0);
        add(hero,text(connection,22,FG,true),8);
        add(hero,text("7 tools  •  No account  •  No server costs",12,MUTED,false),10);
        hero.setOnClickListener(v -> openTool(6)); add(body,hero,24);
        add(body,text("TOOLKIT",11,MUTED,true),28);
        for (int i=0;i<TOOLS.length;i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(15),dp(17),dp(15),dp(17)); row.setBackground(shape(CARD,16));
            TextView icon = text(new String[]{"↗","◎","⇄","≡","◇","⊞","⌁"}[i],26,ACCENT,true);
            icon.setGravity(Gravity.CENTER); row.addView(icon,new LinearLayout.LayoutParams(dp(40),-2));
            LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.setPadding(dp(12),0,dp(10),0);
            add(copy,text(TOOLS[i],17,FG,true),0); add(copy,text(DESCRIPTIONS[i],12,MUTED,false),3);
            row.addView(copy,new LinearLayout.LayoutParams(0,-2,1)); row.addView(text("›",24,MUTED,false));
            row.setContentDescription(TOOLS[i] + ". " + DESCRIPTIONS[i]); row.setFocusable(true); row.setClickable(true);
            row.setOnClickListener(v -> openTool(index)); add(body,row,10);
        }
        add(body,text("Diagnostics run only when you start them. History stays on this device.",12,MUTED,false),20);
    }

    private void navigation() {
        LinearLayout nav = new LinearLayout(this); nav.setPadding(dp(12),dp(8),dp(12),dp(12));
        String[] labels = {"Toolkit","History","About"};
        for (int i=0;i<labels.length;i++) {
            final int tab = i; Button b = button(labels[i],false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(48),1); p.setMargins(dp(4),0,dp(4),0); nav.addView(b,p);
            b.setOnClickListener(v -> {
                if (tab==0) show(-1);
                else if (tab==1) history();
                else about();
            });
        }
        root.addView(nav);
    }

    private void openTool(int i) { cancel(); lastResult=""; currentTarget=""; show(i); }
    private EditText field(String hint, String value) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextColor(FG); input.setHintTextColor(MUTED);
        input.setTextSize(16); input.setHint(hint); input.setText(value); input.setPadding(dp(14),dp(14),dp(14),dp(14));
        input.setBackground(shape(BG,12));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return input;
    }

    private void toolScreen(int tool) {
        add(body,text(DESCRIPTIONS[tool],15,MUTED,false),8);
        LinearLayout inputPanel = panel();
        if (tool!=6) {
            String label = tool==5 ? "IPv4 address / CIDR" : tool==3 ? "Website URL" : "Hostname or IP address";
            add(inputPanel,text(label,12,MUTED,true),0);
            String hint = tool==5 ? "192.168.1.10/24" : tool==3 ? "https://example.com" : "example.com";
            target = field(hint,currentTarget); target.setContentDescription(label); add(inputPanel,target,10);
            if (tool==2) {
                add(inputPanel,text("TCP ports (up to 128)",12,MUTED,true),18);
                extra = field("22,80,443 or 8000-8010",currentPorts); extra.setContentDescription("TCP ports"); add(inputPanel,extra,10);
                add(inputPanel,text("Check hosts you own or have permission to test.",12,MUTED,false),12);
            }
        } else add(inputPanel,text("See the active connection's local addresses, gateway routes and DNS servers.",15,FG,false),0);
        run = button(tool==5 ? "Calculate subnet" : tool==6 ? "Refresh details" : "Run " + TOOLS[tool].toLowerCase(Locale.US),true);
        run.setOnClickListener(v -> execute(tool)); add(inputPanel,run,18);
        stop = button("Stop",false); stop.setVisibility(View.GONE);
        stop.setOnClickListener(v -> { cancel(); setBusy(false); status.setText("Stopped. You can run another check."); });
        add(inputPanel,stop,8);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT)); progress.setVisibility(View.GONE); add(inputPanel,progress,8);
        status = text(lastResult.isEmpty() ? "Ready when you are." : "Saved result restored.",12,MUTED,false); add(inputPanel,status,8);
        add(body,inputPanel,22);
        if (tool==0) add(body,text("Sends 4 ICMP packets. Some networks block ping; use TCP ports to compare.",12,MUTED,false),16);
        if (tool==4) add(body,text("Uses port 443 and the device's trusted certificates. Hostname validation remains enabled.",12,MUTED,false),16);
        LinearLayout outputPanel = panel(); add(outputPanel,text("RESULT",11,ACCENT,true),0);
        result = text(lastResult.isEmpty() ? "Your results will appear here." : lastResult,13,FG,false);
        result.setTypeface(Typeface.MONOSPACE); result.setTextIsSelectable(true); add(outputPanel,result,14);
        LinearLayout actions = new LinearLayout(this);
        Button copy = button("Copy",false), share = button("Share",false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(46),1); p.setMargins(0,0,dp(6),0);
        actions.addView(copy,p); actions.addView(share,new LinearLayout.LayoutParams(0,dp(46),1));
        copy.setOnClickListener(v -> copy(lastResult)); share.setOnClickListener(v -> share(lastResult)); add(outputPanel,actions,18);
        add(body,outputPanel,16);
        if (tool==6 && lastResult.isEmpty()) execute(tool);
    }

    private void captureInputs() {
        if (target!=null) currentTarget=target.getText().toString();
        if (extra!=null) currentPorts=extra.getText().toString();
    }
    private void cancel() { generation++; if (current!=null) current.cancel(true); current=null; }
    private void setBusy(boolean busy) {
        run.setEnabled(!busy); run.setAlpha(busy ? .5f : 1);
        stop.setVisibility(busy ? View.VISIBLE : View.GONE); progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }
    private void execute(int tool) {
        captureInputs(); final String input=currentTarget.trim(), portInput=currentPorts.trim();
        if (tool!=6 && input.isEmpty()) { target.setError("Enter a target first"); target.requestFocus(); return; }
        InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard!=null) keyboard.hideSoftInputFromWindow(root.getWindowToken(),0);
        cancel(); final int token=generation; setBusy(true); status.setText("Running on this device…");
        current=workers.submit(() -> {
            String output; boolean success=true;
            try {
                switch (tool) {
                    case 0: output=NetworkTools.ping(input); break;
                    case 1: output=NetworkTools.dns(input); break;
                    case 2: output=NetworkTools.checkPorts(input,portInput); break;
                    case 3: output=NetworkTools.http(input); break;
                    case 4: output=NetworkTools.tls(input); break;
                    case 5: output=NetworkTools.subnet(input); break;
                    default: output=networkDetails();
                }
            } catch (InterruptedException | CancellationException e) { return; }
            catch (Exception e) { success=false; output="Could not complete this check.\n\n"+(e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage()); }
            final String displayed=output.length()>20000 ? output.substring(0,20000)+"\n[Output truncated]" : output;
            final boolean completed=success;
            main.post(() -> {
                if (token!=generation || isFinishing() || isDestroyed()) return;
                lastResult=displayed; result.setText(displayed); setBusy(false);
                status.setText(completed ? "Completed • saved on this device" : "Check failed • try another target or connection");
                save(tool,input,portInput,displayed,completed);
            });
        });
    }

    private String connectionLabel() {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        Network network=cm.getActiveNetwork(); NetworkCapabilities caps=cm.getNetworkCapabilities(network);
        if (caps==null) return "No active connection";
        String name=caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN connected" : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi connected" : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile data connected" : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet connected" : "Network connected";
        return name + (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "" : " • unverified internet");
    }
    private String networkDetails() {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        Network network=cm.getActiveNetwork(); LinkProperties links=cm.getLinkProperties(network);
        if (links==null) return "No active network.\nConnect to Wi-Fi or mobile data, then refresh.";
        StringBuilder out=new StringBuilder(connectionLabel()+"\n\nInterface: "+links.getInterfaceName()+"\n\nLOCAL ADDRESSES\n");
        for (LinkAddress a:links.getLinkAddresses()) out.append(a).append('\n');
        out.append("\nDNS SERVERS\n"); for (InetAddress dns:links.getDnsServers()) out.append(dns.getHostAddress()).append('\n');
        out.append("\nROUTES\n"); for (RouteInfo route:links.getRoutes()) out.append(route).append('\n');
        if (Build.VERSION.SDK_INT>=28) {
            out.append("\nPrivate DNS: ").append(links.isPrivateDnsActive() ? "Active" : "Inactive");
            if (links.getPrivateDnsServerName()!=null) out.append("\nPrivate DNS host: ").append(links.getPrivateDnsServerName());
        }
        out.append("\n\nThese are local network details; no public-IP service is contacted.\nWi-Fi names and MAC addresses are not collected.");
        return out.toString();
    }

    private JSONArray records() {
        try { return new JSONArray(getSharedPreferences("nettools",MODE_PRIVATE).getString("history","[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }
    private void save(int tool,String input,String ports,String output,boolean success) {
        try {
            JSONArray old=records(), items=new JSONArray();
            JSONObject entry=new JSONObject(); entry.put("tool",tool); entry.put("target",input); entry.put("ports",ports);
            entry.put("output",output); entry.put("time",System.currentTimeMillis()); entry.put("success",success); items.put(entry);
            for (int i=0;i<Math.min(24,old.length());i++) items.put(old.get(i));
            getSharedPreferences("nettools",MODE_PRIVATE).edit().putString("history",items.toString()).apply();
        } catch (JSONException ignored) { /* A malformed history entry never breaks diagnostics. */ }
    }
    private void copy(String value) {
        if (value.isEmpty()) { Toast.makeText(this,"Run a tool first",Toast.LENGTH_SHORT).show(); return; }
        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("NetTools result",value)); Toast.makeText(this,"Result copied",Toast.LENGTH_SHORT).show();
    }
    private void share(String value) {
        if (value.isEmpty()) { Toast.makeText(this,"Run a tool first",Toast.LENGTH_SHORT).show(); return; }
        Intent intent=new Intent(Intent.ACTION_SEND); intent.setType("text/plain"); intent.putExtra(Intent.EXTRA_TEXT,value);
        startActivity(Intent.createChooser(intent,"Share NetTools result"));
    }
    private void history() {
        body.removeAllViews(); add(body,text("Recent checks",30,FG,true),8);
        add(body,text("Your last 25 results, stored only on this device.",14,MUTED,false),8);
        JSONArray items=records();
        if (items.length()==0) add(body,text("No checks yet. Pick a tool to get started.",16,MUTED,false),30);
        for (int i=0;i<items.length();i++) {
            JSONObject entry=items.optJSONObject(i); if (entry==null) continue;
            int tool=entry.optInt("tool",-1); if (tool<0 || tool>=TOOLS.length) continue;
            LinearLayout card=panel(); add(card,text(TOOLS[tool]+(entry.optBoolean("success") ? "" : " • failed"),17,FG,true),0);
            add(card,text(entry.optString("target","Device connection"),14,ACCENT,false),6);
            add(card,text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(entry.optLong("time"))),12,MUTED,false),8);
            final JSONObject item=entry;
            card.setOnClickListener(v -> {
                TextView detail=text(item.optString("output"),13,FG,false); detail.setTypeface(Typeface.MONOSPACE); detail.setTextIsSelectable(true); detail.setPadding(dp(18),dp(12),dp(18),dp(12));
                ScrollView scroll=new ScrollView(this); scroll.addView(detail);
                new AlertDialog.Builder(this).setTitle(TOOLS[tool]).setView(scroll)
                        .setPositiveButton("Copy",(dialog,which)->copy(item.optString("output")))
                        .setNeutralButton("Share",(dialog,which)->share(item.optString("output")))
                        .setNegativeButton("Close",null).show();
            }); add(body,card,12);
        }
        if (items.length()>0) {
            Button clear=button("Clear history",false); clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Clear saved checks?").setMessage("Remove the results saved on this device.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{
                        getSharedPreferences("nettools",MODE_PRIVATE).edit().remove("history").apply(); history();
                    }).show()); add(body,clear,20);
        }
    }
    private void about() {
        body.removeAllViews(); add(body,text("Small toolkit.\nUseful answers.",30,FG,true),8);
        add(body,text("NetTools 0.1.0\nBuilt for everyday network troubleshooting.",16,MUTED,false),14);
        LinearLayout card=panel();
        add(card,text("Private by design",20,FG,true),0);
        add(card,text("No login, ads, analytics or cloud database. Diagnostics connect directly to the target you choose. DNS uses your device's configured resolver. Results stay on your phone until you copy or share them.",15,MUTED,false),12);
        add(card,text("Runs while you use the app",20,FG,true),22);
        add(card,text("This version is a manual toolkit. It does not continuously monitor servers or run scheduled checks in the background.",15,MUTED,false),12);
        add(card,text("Download outside Google Play",20,FG,true),22);
        add(card,text("The project and APK downloads are available through GitHub. Preview builds are for testing; production releases use a permanent private signing key.",15,MUTED,false),12);
        add(body,card,24);
        Button github=button("Open GitHub downloads",true); github.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/benjaminbencsik/NetTools/releases")))); add(body,github,20);
    }
}
