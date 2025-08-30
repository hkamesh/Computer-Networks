// Save as: DisasterCommApp.java
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

/**
 * DisasterCommApp (Milestone 2 + Messaging + DVR Nearest + Map Click + Approx GPS)
 * - Free OSM (Leaflet) inside WebView
 * - Search city (Nominatim) + Overpass POIs (hospitals, police, rescuers)
 * - Role-based visibility (Hospital/Police/Rescuer/Survivor)
 * - Messaging: Visible / All / specific types / nearest
 * - Map click sets user location; "Use GPS (IP)" approximate geolocation
 * - Nearest by Role (DVR): builds k-NN graph and runs Bellman-Ford to nearest target
 * - Haversine distance; draws multi-hop polyline; blinking target highlight
 *
 * Run (example):
 * javac --module-path "C:\\javafx-sdk-21\\lib" --add-modules javafx.controls,javafx.web DisasterCommApp.java
 * java  --module-path "C:\\javafx-sdk-21\\lib" --add-modules javafx.controls,javafx.web DisasterCommApp
 */
public class DisasterCommApp extends Application {

    private WebEngine webEngine;
    private TextField cityField;
    private ComboBox<String> roleBox;

    // Messaging UI
    private TextArea logArea;
    private ComboBox<String> recipientBox;
    private TextField messageField;

    // Location / Nearest UI
    private Button gpsBtn, clearLocBtn, findNearestBtn;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Smart Disaster Communication Network — OSM (Free) + Messaging + DVR");

        // Top bar: city + role + load
        ToolBar top = new ToolBar();
        top.setOrientation(Orientation.HORIZONTAL);
        top.setPadding(new Insets(6));
        top.setStyle("-fx-font-size: 12px;");
        Label lblCity = new Label("City:");
        cityField = new TextField();
        cityField.setPromptText("e.g., Chennai, New York, Tokyo");
        cityField.setPrefColumnCount(24);

        Label lblRole = new Label("Role:");
        roleBox = new ComboBox<>();
        roleBox.getItems().addAll("Hospital", "Police", "Rescuer", "Survivor");
        roleBox.getSelectionModel().select("Survivor"); // default

        Button loadBtn = new Button("Load");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        top.getItems().addAll(lblCity, cityField, new Separator(), lblRole, roleBox, new Separator(), loadBtn);

        // Map view
        WebView webView = new WebView();
        webView.setContextMenuEnabled(false);
        webEngine = webView.getEngine();
        webEngine.loadContent(buildHtmlTemplate());

        // Events
        loadBtn.setOnAction(e -> {
            String city = cityField.getText().trim();
            if (!city.isEmpty()) {
                String js = "window.loadCity(" + toJsString(city) + ");";
                try { webEngine.executeScript(js); } catch (Exception ex) { alertJS("Map not ready or JS error: " + ex.getMessage()); }
                // apply role (in case picked earlier)
                String role = roleBox.getSelectionModel().getSelectedItem();
                try { webEngine.executeScript("window.applyRole(" + toJsString(role) + ");"); } catch (Exception ex) {}
                appendLog("Loaded city: " + city + "  | role: " + role);
            }
        });

        cityField.setOnAction(e -> loadBtn.fire());

        roleBox.setOnAction(e -> {
            String role = roleBox.getSelectionModel().getSelectedItem();
            try { webEngine.executeScript("window.applyRole(" + toJsString(role) + ");"); } catch (Exception ex) { /* ignore */ }
            appendLog("Role selected: " + role);
        });

        // Right side: messaging + location + nearest
        VBox right = new VBox(10);
        right.setStyle("-fx-background-color: #fafafa;");
        right.setPadding(new Insets(10));
        right.setPrefWidth(380);

        // Location section
        TitledPane locationPane = new TitledPane();
        locationPane.setText("Location");
        VBox locBox = new VBox(8);
        Label locHelp = new Label("Click on the map to set your location.\nOr use approximate GPS by IP.");
        gpsBtn = new Button("Use GPS (IP)");
        gpsBtn.setOnAction(e -> {
            try { webEngine.executeScript("window.locateMe()"); appendLog("Attempting approximate GPS via IP..."); }
            catch (Exception ex){ alertJS("GPS failed: " + ex.getMessage()); }
        });
        clearLocBtn = new Button("Clear My Location");
        clearLocBtn.setOnAction(e -> {
            try { webEngine.executeScript("window.clearUserLocation()"); appendLog("Cleared user location."); }
            catch (Exception ex){ alertJS("Clear failed: " + ex.getMessage()); }
        });
        locBox.getChildren().addAll(locHelp, gpsBtn, clearLocBtn);
        locationPane.setContent(locBox);
        locationPane.setExpanded(true);

        // Nearest section (DVR)
        TitledPane nearestPane = new TitledPane();
        nearestPane.setText("Nearest (by Role) — DVR");
        VBox nearestBox = new VBox(8);
        Label nearestLbl = new Label("Find the nearest destination for your role:\n" +
                "• Survivor → nearest Hospital/Police/Rescuer\n" +
                "• Rescuer → nearest Survivor\n" +
                "• Police/Hospital → nearest Survivor");
        findNearestBtn = new Button("Find Nearest (by Role)");
        findNearestBtn.setOnAction(e -> runFindNearestByRole());
        HBox quickRow = new HBox(8);
        Button btnH = new Button("Nearest Hospital");
        Button btnP = new Button("Nearest Police");
        Button btnR = new Button("Nearest Rescuer");
        Button btnS = new Button("Nearest Survivor");
        btnH.setOnAction(e -> runFindNearestOf("Hospital"));
        btnP.setOnAction(e -> runFindNearestOf("Police"));
        btnR.setOnAction(e -> runFindNearestOf("Rescuer"));
        btnS.setOnAction(e -> runFindNearestOf("Survivor"));
        quickRow.getChildren().addAll(btnH, btnP, btnR, btnS);
        nearestBox.getChildren().addAll(nearestLbl, findNearestBtn, quickRow);
        nearestPane.setContent(nearestBox);
        nearestPane.setExpanded(true);

        // Message log
        TitledPane msgPane = new TitledPane();
        msgPane.setText("Messaging");
        VBox msgBox = new VBox(8);
        Label lblLog = new Label("Message Log:");
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(12);

        Label lblRecipient = new Label("Recipient:");
        recipientBox = new ComboBox<>();
        recipientBox.getItems().addAll(
                "Visible",           // sends to what the current role sees (role filtered)
                "All",
                "Hospitals",
                "Police",
                "Rescuers",
                "Survivors",
                "Nearest Rescuer",
                "Nearest Hospital"
        );
        recipientBox.getSelectionModel().select("Visible");

        messageField = new TextField();
        messageField.setPromptText("Type message...");

        HBox sendRow = new HBox(8);
        Button sendBtn = new Button("Send");
        sendBtn.setOnAction(ev -> sendMessage());
        Button sosBtn = new Button("Simulate SOS");
        sosBtn.setOnAction(ev -> simulateSOS());
        sendRow.getChildren().addAll(sendBtn, sosBtn);

        msgBox.getChildren().addAll(lblLog, logArea, lblRecipient, recipientBox, messageField, sendRow);
        msgPane.setContent(msgBox);
        msgPane.setExpanded(true);

        ScrollPane rightScroll = new ScrollPane(right);
        rightScroll.setFitToWidth(true);
        right.getChildren().addAll(locationPane, nearestPane, msgPane);

        // Layout
        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(webView);
        root.setRight(rightScroll);

        Scene scene = new Scene(root, 1280, 820);
        stage.setScene(scene);
        stage.setMinWidth(1100);
        stage.setMinHeight(720);
        stage.show();
    }

    // Append to Java-side message log
    private void appendLog(String s) {
        logArea.appendText("[" + java.time.LocalTime.now().withNano(0) + "] " + s + "\n");
        logArea.setScrollTop(Double.MAX_VALUE);
    }

    // Send message from selected role to chosen recipient(s)
    private void sendMessage() {
        String role = roleBox.getSelectionModel().getSelectedItem();
        String recipient = recipientBox.getSelectionModel().getSelectedItem();
        String message = messageField.getText().trim();
        if (message.isEmpty()) { alertUI("Type a message to send."); return; }

        String payload = role + ": " + message;

        try {
            Object result = null;
            switch (recipient) {
                case "Visible":
                    result = webEngine.executeScript("window.broadcastVisible(" + toJsString(payload) + ");");
                    break;
                case "All":
                    result = webEngine.executeScript("window.broadcastToType('All'," + toJsString(payload) + ");");
                    break;
                case "Hospitals":
                    result = webEngine.executeScript("window.broadcastToType('Hospital'," + toJsString(payload) + ");");
                    break;
                case "Police":
                    result = webEngine.executeScript("window.broadcastToType('Police'," + toJsString(payload) + ");");
                    break;
                case "Rescuers":
                    result = webEngine.executeScript("window.broadcastToType('Rescuer'," + toJsString(payload) + ");");
                    break;
                case "Survivors":
                    result = webEngine.executeScript("window.broadcastToType('Survivor'," + toJsString(payload) + ");");
                    break;
                case "Nearest Rescuer":
                    result = webEngine.executeScript(
                            "window.findNearestAndHighlight('Rescuer', true, true)");
                    break;
                case "Nearest Hospital":
                    result = webEngine.executeScript(
                            "window.findNearestAndHighlight('Hospital', true, true)");
                    break;
                default:
                    result = webEngine.executeScript("window.broadcastToType('All'," + toJsString(payload) + ");");
                    break;
            }
            int delivered = (result instanceof Number) ? ((Number) result).intValue() : 0;
            appendLog("Sent → [" + recipient + "] \"" + message + "\" (delivered to " + delivered + " target(s))");
            messageField.clear();
        } catch (Exception ex) {
            alertJS("JS call failed: " + ex.getMessage());
            appendLog("Failed to send message (JS error).");
        }
    }

    // A simple SOS helper
    private void simulateSOS() {
        String role = "Survivor";
        String payload = "SOS! Immediate help required!";
        try {
            Object res1 = webEngine.executeScript(
                    "window.findNearestAndHighlight('Rescuer', true, true)");
            int d1 = (res1 instanceof Number) ? ((Number) res1).intValue() : 0;

            Object res2 = webEngine.executeScript("window.broadcastToType('Hospital'," + toJsString(role + ": " + payload) + ");");
            int d2 = (res2 instanceof Number) ? ((Number) res2).intValue() : 0;

            appendLog("SOS simulated → Nearest Rescuer notified: " + d1 + " | Hospitals broadcast: " + d2);
        } catch (Exception ex) {
            alertJS("Failed to simulate SOS: " + ex.getMessage());
        }
    }

    private void runFindNearestByRole() {
        String role = roleBox.getSelectionModel().getSelectedItem();
        try {
            Object res = webEngine.executeScript("window.findNearestByRole(" + toJsString(role) + ", true)");
            if (res != null) appendLog(String.valueOf(res));
        } catch (Exception ex) {
            alertJS("Nearest-by-role failed: " + ex.getMessage());
        }
    }

    private void runFindNearestOf(String type) {
        try {
            Object res = webEngine.executeScript("window.findNearestAndHighlight(" + toJsString(type) + ", true, false)");
            if (res != null) appendLog(String.valueOf(res));
        } catch (Exception ex) {
            alertJS("Nearest failed: " + ex.getMessage());
        }
    }

    private void alertUI(String msg) {
        appendLog("[UI] " + msg);
    }

    private void alertJS(String msg) {
        appendLog("[JS] " + msg);
    }

    /** Escape a Java string for JS single-quoted literal */
    private String toJsString(String s) {
        if (s == null) return "''";
        String t = s.replace("\\", "\\\\").replace("'", "\\'");
        return "'" + t + "'";
    }

    /**
     * HTML/JS template (Leaflet + Nominatim + Overpass + DVR)
     */
    private String buildHtmlTemplate() {
        return """
<!doctype html>
<html>
<head>
<meta charset="utf-8"/>
<meta http-equiv="Content-Security-Policy"
      content="default-src 'self' https: http: data: blob:; style-src 'self' https: 'unsafe-inline';
               img-src * data: blob:; script-src 'self' https: 'unsafe-inline' 'unsafe-eval';">
<title>Disaster Map</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
      integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=" crossorigin="anonymous"/>
<style>
  html, body { height:100%; margin:0; }
  #map { width:100%; height:100vh; }
  .legend {
    position:absolute; top:12px; right:12px; background:#fff; padding:10px 12px;
    border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,.15); font-family: system-ui, sans-serif; font-size:13px;
    z-index:9999; max-width:220px;
  }
  .legend h4 { margin:0 0 6px 0; font-size:13px; font-weight:600; }
  .legend .row { margin:5px 0; display:flex; align-items:center; }
  .dot { width:11px; height:11px; border-radius:50%; margin-right:8px; display:inline-block; }
  .hospital { background:#2e7d32; }
  .police   { background:#1565c0; }
  .rescuer  { background:#e65100; }
  .survivor { background:#c2185b; }
  .user     { background:#222; }

  .hint {
    position:absolute; left:12px; bottom:12px; background:rgba(255,255,255,.95); padding:8px 10px; font-size:12px;
    border-radius:8px; box-shadow:0 2px 8px rgba(0,0,0,.12); z-index:9999; max-width: 320px;
  }
  .loading {
    position:absolute; top:12px; left:12px; background:#fff3cd; color:#8a6d3b; padding:6px 10px;
    border:1px solid #faebcc; border-radius:6px; z-index:9999; display:none;
  }
</style>
</head>
<body>
<div id="map"></div>

<div class="legend">
  <h4>Legend</h4>
  <div class="row"><span class="dot hospital"></span>Hospitals</div>
  <div class="row"><span class="dot police"></span>Police</div>
  <div class="row"><span class="dot rescuer"></span>Rescuers</div>
  <div class="row"><span class="dot survivor"></span>Survivors (simulated)</div>
  <div class="row"><span class="dot user"></span>Your Location</div>
</div>
<div class="hint">Type a city in the app and click <b>Load</b>.<br/>
Click on the map to set your location — it will auto-find the nearest (by role).
Use the right panel for GPS (IP) or manual nearest.</div>
<div id="busy" class="loading">Loading map data…</div>

<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
        integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=" crossorigin="anonymous"></script>
<script>
  // --- Map setup ---
  const map = L.map('map', { worldCopyJump: true });
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
     maxZoom: 19, attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);
  map.setView([20.5937, 78.9629], 5); // default: India

  const busy = document.getElementById('busy');

  // Marker registry
  window.allMarkers = [];   // {lat, lon, name, type}
  window._markerObjs = [];  // Leaflet objects

  // layers
  const hospitalsLayer = L.layerGroup().addTo(map);
  const policeLayer    = L.layerGroup().addTo(map);
  const rescuersLayer  = L.layerGroup().addTo(map);
  const survivorsLayer = L.layerGroup().addTo(map);

  // Your location + route visuals
  let userMarker = null;
  let routeLine = null;
  let pulseTimer = null;
  let pulseCircle = null;

  function setBusy(on) { busy.style.display = on ? 'block' : 'none'; }

  function registerMarker(layer, lat, lon, name, type) {
    const colorMap = { 'Hospital':'#2e7d32','Police':'#1565c0','Rescuer':'#e65100','Survivor':'#c2185b' };
    const color = colorMap[type] || '#444';
    const m = L.circleMarker([lat, lon], { radius:6, color:color, fillColor:color, fillOpacity:0.9, weight:1 })
             .bindPopup('<b>' + (type||'') + '</b><br/>' + (name||''));
    m.addTo(layer);
    window._markerObjs.push(m);
    window.allMarkers.push({ lat: lat, lon: lon, name: name||'', type: type||'' });
    return m;
  }

  function addHospital(lat, lon, name){ registerMarker(hospitalsLayer, lat, lon, name, 'Hospital'); }
  function addPolice(lat, lon, name){ registerMarker(policeLayer, lat, lon, name, 'Police'); }
  function addRescuer(lat, lon, name){ registerMarker(rescuersLayer, lat, lon, name, 'Rescuer'); }
  function addSurvivor(lat, lon, name){ registerMarker(survivorsLayer, lat, lon, name, 'Survivor'); }

  function clearAll() {
    hospitalsLayer.clearLayers();
    policeLayer.clearLayers();
    rescuersLayer.clearLayers();
    survivorsLayer.clearLayers();
    window.allMarkers = [];
    window._markerObjs = [];
    clearRoute();
  }

  function clearRoute(){
    if(routeLine){ map.removeLayer(routeLine); routeLine=null; }
    if(pulseCircle){ map.removeLayer(pulseCircle); pulseCircle=null; }
    if(pulseTimer){ clearInterval(pulseTimer); pulseTimer=null; }
  }

  function setUserLocation(lat, lon, zoomTo=true){
    if(userMarker){ map.removeLayer(userMarker); userMarker=null; }
    window.userLocation = [lat, lon];
    userMarker = L.circleMarker([lat, lon], { radius:7, color:'#222', fillColor:'#222', fillOpacity:1, weight:2 })
      .bindPopup('<b>You</b><br/>' + lat.toFixed(5) + ', ' + lon.toFixed(5))
      .addTo(map).openPopup();
    if(zoomTo) map.setView([lat, lon], Math.max(map.getZoom(), 13));
  }

  // Map click: set location + auto-find nearest by role (from Java)
  map.on('click', (e)=>{
    setUserLocation(e.latlng.lat, e.latlng.lng, false);
    if(window._currentRole){
      // Auto-run nearest based on role + show route (DVR)
      setTimeout(()=>window.findNearestByRole(window._currentRole, true), 120);
    }
  });

  // approximate GPS via IP (fallback since navigator.geolocation may not be supported in WebView)
  window.locateMe = async function(){
    try {
      // Try navigator.geolocation if available
      if(navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
          pos => {
            const lat = pos.coords.latitude, lon = pos.coords.longitude;
            setUserLocation(lat, lon, true);
          },
          async err => { await ipFallback(); }
        );
      } else {
        await ipFallback();
      }
    } catch(e){ console.error(e); await ipFallback(); }
  };

  async function ipFallback(){
    try {
      const resp = await fetch('https://ipapi.co/json/');
      if(!resp.ok) throw new Error('ipapi HTTP ' + resp.status);
      const j = await resp.json();
      if(j && j.latitude && j.longitude){
        setUserLocation(j.latitude, j.longitude, true);
      } else { alert('Could not determine location by IP. Click on map instead.'); }
    } catch(e) {
      console.error(e);
      alert('IP geolocation failed. Click on map to set your location.');
    }
  }

  window.clearUserLocation = function(){
    if(userMarker){ map.removeLayer(userMarker); userMarker=null; }
    window.userLocation = null;
    clearRoute();
  };

  // simple highlight for messaging
  function highlightMarker(idx, message) {
    try {
      const m = window._markerObjs[idx];
      if(!m) return;
      const mk = window.allMarkers[idx];
      if(message) {
        m.bindPopup('<b>' + (mk.type||'') + '</b><br/>' + (mk.name||'') + '<br/><i>'+message+'</i>');
      }
      m.openPopup();
      const orig = (m.getRadius && m.getRadius()) || 6;
      try { m.setStyle({ radius: orig + 5 }); } catch(e) {}
      setTimeout(() => { try { m.setStyle({ radius: orig }); } catch(e) {} }, 900);
    } catch(e) { console.error(e); }
  }

  window.broadcastToType = function(type, message) {
    if(!window.allMarkers || !window._markerObjs) return 0;
    let count = 0;
    for(let i=0;i<window.allMarkers.length;i++){
      const mk = window.allMarkers[i];
      if(type === 'All' || !type || mk.type.toLowerCase() === type.toLowerCase()){
        highlightMarker(i, message);
        count++;
      }
    }
    return count;
  };

  window.broadcastVisible = function(message) {
    if(!window.allMarkers || !window._markerObjs) return 0;
    let count = 0;
    for(let i=0;i<window.allMarkers.length;i++){
      const mk = window.allMarkers[i];
      const t = mk.type.toLowerCase();
      if((t==='hospital' && map.hasLayer(hospitalsLayer)) ||
         (t==='police' && map.hasLayer(policeLayer)) ||
         (t==='rescuer' && map.hasLayer(rescuersLayer)) ||
         (t==='survivor' && map.hasLayer(survivorsLayer)) ){
        highlightMarker(i, message); count++;
      }
    }
    return count;
  };

  // --- Distance / DVR helpers ---
  function haversine(lat1, lon1, lat2, lon2) {
    const R = 6371000; // meters
    const toRad = x => x * Math.PI / 180;
    const dLat = toRad(lat2 - lat1);
    const dLon = toRad(lon2 - lon1);
    const a = Math.sin(dLat/2)**2 +
              Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
              Math.sin(dLon/2)**2;
    return 2 * R * Math.asin(Math.sqrt(a));
  }

  function buildGraph(nodes, k=4, maxEdgeMeters=8000){
    // nodes: [{lat, lon, type, name}]
    // edges: adjacency list {i: [{j, w}]}
    const edges = Array(nodes.length).fill(0).map(()=>[]);
    for(let i=0;i<nodes.length;i++){
      const cand = [];
      for(let j=0;j<nodes.length;j++){
        if(i===j) continue;
        const d = haversine(nodes[i].lat, nodes[i].lon, nodes[j].lat, nodes[j].lon);
        cand.push({j, d});
      }
      cand.sort((a,b)=>a.d-b.d);
      let added=0;
      for(let c of cand){
        if(added>=k) break;
        if(c.d <= maxEdgeMeters){
          edges[i].push({ j:c.j, w:c.d });
          added++;
        }
      }
    }
    return edges;
  }

  function bellmanFord(nodes, edges, s){
    const n = nodes.length;
    const dist = Array(n).fill(Infinity);
    const prev = Array(n).fill(-1);
    dist[s] = 0;

    // relax edges up to n-1 times
    for(let iter=0; iter<n-1; iter++){
      let any=false;
      for(let u=0; u<n; u++){
        for(let e of edges[u]){
          if(dist[u]+e.w < dist[e.j]){
            dist[e.j] = dist[u]+e.w;
            prev[e.j] = u;
            any = true;
          }
        }
      }
      if(!any) break;
    }
    return { dist, prev };
  }

  function pathTo(prev, target){
    const p = [];
    let t = target;
    while(t !== -1){
      p.push(t);
      t = prev[t];
    }
    p.reverse();
    return p;
  }

  function drawRoute(path, nodes){
    clearRoute();
    const latlngs = path.map(idx => [nodes[idx].lat, nodes[idx].lon]);
    routeLine = L.polyline(latlngs, {color:'#d32f2f', weight:4, opacity:0.85}).addTo(map);
    // pulse at the destination
    const end = latlngs[latlngs.length-1];
    pulseCircle = L.circleMarker(end, { radius:10, color:'#d32f2f', fillColor:'#d32f2f', fillOpacity:0.7 }).addTo(map);
    let grow=true, r=10;
    pulseTimer = setInterval(()=>{
      r += (grow?1:-1);
      if(r>=16) grow=false;
      if(r<=10) grow=true;
      try { pulseCircle.setRadius(r); } catch(e){}
    }, 80);
    map.fitBounds(routeLine.getBounds(), { padding:[30,30] });
  }

  function nearestTargetIndex(nodes, distArr, targetTypes){
    let bestIdx = -1, bestD = Infinity;
    for(let i=0;i<nodes.length;i++){
      if(targetTypes.has(nodes[i].type)){
        if(distArr[i] < bestD){
          bestD = distArr[i]; bestIdx = i;
        }
      }
    }
    return bestIdx;
  }

  // Public: find nearest of TYPE; draw route if requested; optionally also message the target
  window.findNearestAndHighlight = function(type, drawRouteFlag, alsoMessage){
    // ensure we have some markers
    if(!window.allMarkers || window.allMarkers.length===0) {
      alert('No map markers available yet. Load a city first.');
      return 0;
    }
    const user = window.userLocation || window.lastCenter;
    if(!user){ alert('Set your location (click on map) or use GPS/IP.'); return 0; }

    const nodes = []; // copy of markers
    for(const mk of window.allMarkers){
      nodes.push({ lat: mk.lat, lon: mk.lon, type: mk.type, name: mk.name });
    }
    // push user as source
    const sourceIdx = nodes.length;
    nodes.push({ lat:user[0], lon:user[1], type:'User', name:'You' });

    const edges = buildGraph(nodes, 4, 8000);
    const bf = bellmanFord(nodes, edges, sourceIdx);

    const targetSet = new Set([type]);
    const tIdx = nearestTargetIndex(nodes, bf.dist, targetSet);
    if(tIdx === -1 || bf.dist[tIdx] === Infinity){
      alert('No reachable ' + type + ' found (graph too sparse). Try clicking nearer to city.');
      return 0;
    }

    const p = pathTo(bf.prev, tIdx);
    if(drawRouteFlag) drawRoute(p, nodes);

    const meters = bf.dist[tIdx] | 0;
    const km = (meters/1000).toFixed(2);
    const hops = Math.max(0, p.length-1);
    const name = nodes[tIdx].name || type;

    // Optionally mark target
    if(alsoMessage){
      // find original index of that target (not user)
      let delivered=0;
      for(let i=0;i<window.allMarkers.length;i++){
        if(Math.abs(window.allMarkers[i].lat - nodes[tIdx].lat) < 1e-7 &&
           Math.abs(window.allMarkers[i].lon - nodes[tIdx].lon) < 1e-7 &&
           window.allMarkers[i].type === nodes[tIdx].type){
          highlightMarker(i, 'Nearest target via DVR ('+km+' km, '+hops+' hops)');
          delivered++;
          break;
        }
      }
      return delivered;
    }

    return "Nearest " + type + " → " + name + " | " + km + " km | hops: " + hops;
  };

  // Public: nearest by role mapping
  window.findNearestByRole = function(role, drawRouteFlag){
    const userRole = (role||'Survivor').toLowerCase();
    if(userRole === 'survivor'){
      // choose nearest among Hospital/Police/Rescuer
      const r1 = window.findNearestAndHighlight('Hospital', drawRouteFlag, false);
      const r2 = window.findNearestAndHighlight('Police', drawRouteFlag, false);
      const r3 = window.findNearestAndHighlight('Rescuer', drawRouteFlag, false);
      // r1/r2/r3 are strings with distances text or 0 if failure; compute directly again via internal function:
      // For correctness, re-run once to determine which is closest: we will compute distances quickly now:
      const result = _nearestAmong(['Hospital','Police','Rescuer'], drawRouteFlag);
      return result;
    } else if(userRole === 'rescuer'){
      return window.findNearestAndHighlight('Survivor', drawRouteFlag, false);
    } else if(userRole === 'police' || userRole === 'hospital'){
      return window.findNearestAndHighlight('Survivor', drawRouteFlag, false);
    } else {
      return "Unknown role.";
    }
  };

  function _nearestAmong(types, drawRouteFlag){
    const user = window.userLocation || window.lastCenter;
    if(!user){ alert('Set your location first.'); return 0; }
    const nodes = [];
    for(const mk of window.allMarkers){
      nodes.push({ lat: mk.lat, lon: mk.lon, type: mk.type, name: mk.name });
    }
    const sourceIdx = nodes.length;
    nodes.push({ lat:user[0], lon:user[1], type:'User', name:'You' });
    const edges = buildGraph(nodes, 4, 8000);
    const bf = bellmanFord(nodes, edges, sourceIdx);
    let best = { idx:-1, type:null, dist:Infinity };
    for(const t of types){
      const set = new Set([t]);
      const idx = nearestTargetIndex(nodes, bf.dist, set);
      if(idx !== -1 && bf.dist[idx] < best.dist){ best = { idx, type:t, dist: bf.dist[idx] }; }
    }
    if(best.idx === -1){
      alert('No reachable target among: ' + types.join(', '));
      return 0;
    }
    const p = pathTo(bf.prev, best.idx);
    if(drawRouteFlag) drawRoute(p, nodes);
    const km = (best.dist/1000).toFixed(2);
    const hops = Math.max(0, p.length-1);
    const name = nodes[best.idx].name || best.type;
    return "Nearest " + best.type + " → " + name + " | " + km + " km | hops: " + hops;
  }

  // helpful debug
  window.getMarkersJSON = function() { return JSON.stringify(window.allMarkers); };

  // --- Overpass + Nominatim (with light throttling + caps) ---
  let isLoading = false;
  async function fetchJson(url){
     const resp = await fetch(url, { headers: { 'Accept': 'application/json' }});
     if (!resp.ok) throw new Error('HTTP ' + resp.status);
     return await resp.json();
  }

  function overpassAround(lat, lon, radiusMeters, overpassQl) {
     const base = 'https://overpass-api.de/api/interpreter?data=';
     const q = `[out:json][timeout:25];
       (
         ${overpassQl.replaceAll('{LAT}', lat).replaceAll('{LON}', lon).replaceAll('{RADIUS}', radiusMeters)}
       );
       out center;`;
     return base + encodeURIComponent(q);
  }

  function hospitalsPoliceQL(){
     const parts = [];
     ['node','way','relation'].forEach(t => {
        parts.push(`${t}["amenity"="hospital"](around:{RADIUS},{LAT},{LON});`);
        parts.push(`${t}["amenity"="clinic"](around:{RADIUS},{LAT},{LON});`);
        parts.push(`${t}["amenity"="police"](around:{RADIUS},{LAT},{LON});`);
     });
     return parts.join('\\n');
  }
  function rescuersQL(){
     const parts = [];
     ['node','way','relation'].forEach(t => {
        parts.push(`${t}["amenity"="fire_station"](around:{RADIUS},{LAT},{LON});`);
        parts.push(`${t}["emergency"="ambulance_station"](around:{RADIUS},{LAT},{LON});`);
     });
     return parts.join('\\n');
  }

  function simulateSurvivors(centerLat, centerLon, count=10, spreadMeters=3000){
    const pts = [];
    for(let i=0;i<count;i++){
      const dLat = (Math.random()-0.5) * (spreadMeters / 111320);
      const dLon = (Math.random()-0.5) * (spreadMeters / (111320 * Math.cos(centerLat * Math.PI/180)));
      pts.push([centerLat + dLat, centerLon + dLon]);
    }
    return pts;
  }

  window.loadCity = async function(city){
    if(isLoading) return;
    isLoading = true; setBusy(true);
    try {
      clearAll();
      // Geocode via Nominatim
      const url = 'https://nominatim.openstreetmap.org/search?format=json&q=' + encodeURIComponent(city);
      const res = await fetchJson(url);
      if(!res || !res.length){ alert('City not found: ' + city); isLoading=false; setBusy(false); return; }
      const place = res[0];
      const lat = parseFloat(place.lat), lon = parseFloat(place.lon);
      window.lastCenter = [lat, lon];

      if(place.boundingbox && place.boundingbox.length===4){
        const bb = place.boundingbox.map(parseFloat);
        const sw = L.latLng(bb[0], bb[2]);
        const ne = L.latLng(bb[1], bb[3]);
        map.fitBounds(L.latLngBounds(sw, ne), { padding:[20,20] });
      } else {
        map.setView([lat,lon], 12);
      }

      // Hospitals + Police (cap results to avoid lag)
      const hpUrl = overpassAround(lat, lon, 9000, hospitalsPoliceQL());
      const hp = await fetchJson(hpUrl);
      let cHosp=0, cPolice=0;
      (hp.elements||[]).forEach(el => {
        let clat=null, clon=null, name=null;
        if(el.type==='node'){ clat=el.lat; clon=el.lon; }
        else if((el.type==='way'||el.type==='relation') && el.center){ clat=el.center.lat; clon=el.center.lon; }
        if(el.tags && el.tags.name) name=el.tags.name;

        if(el.tags && (el.tags.amenity==='hospital' || el.tags.amenity==='clinic')){
          if(clat && clon && cHosp<250){ addHospital(clat,clon,name); cHosp++; }
        } else if(el.tags && el.tags.amenity==='police'){
          if(clat && clon && cPolice<250){ addPolice(clat,clon,name); cPolice++; }
        }
      });

      // Rescuers (fire/ambulance stations)
      const rUrl = overpassAround(lat, lon, 11000, rescuersQL());
      const rr = await fetchJson(rUrl);
      let cResc=0;
      (rr.elements||[]).forEach(el => {
        let clat=null, clon=null, name=null;
        if(el.type==='node'){ clat=el.lat; clon=el.lon; }
        else if((el.type==='way'||el.type==='relation') && el.center){ clat=el.center.lat; clon=el.center.lon; }
        if(el.tags && el.tags.name) name=el.tags.name;
        if(clat && clon && cResc<250) { addRescuer(clat,clon,name); cResc++; }
      });

      // Survivors (simulated around center)
      simulateSurvivors(lat, lon, 10, 3000).forEach(([sLat, sLon], idx) => {
        addSurvivor(sLat, sLon, 'SOS ping #' + (idx+1));
      });

      // apply role visibility if window._currentRole set by Java
      if(window._currentRole) window.applyRole(window._currentRole);

    } catch(err){
      console.error(err);
      alert('Error: ' + err.message);
    } finally {
      isLoading=false; setBusy(false);
    }
  };

  // Role-based visibility rules
  function setLayer(layer, visible){
    if(visible){ if(!map.hasLayer(layer)) layer.addTo(map); }
    else { if(map.hasLayer(layer)) map.removeLayer(layer); }
  }

  window.applyRole = function(role){
    window._currentRole = role || 'Survivor';
    const r = (role||'Survivor').toLowerCase();
    let showHosp=false, showPolice=false, showResc=false, showSurv=false;
    if(r==='hospital'){ showResc=true; showSurv=true; }
    else if(r==='police'){ showHosp=true; showResc=true; showSurv=true; }
    else if(r==='rescuer'){ showHosp=true; showSurv=true; }
    else { /* survivor (default) */ showHosp=true; showPolice=true; showResc=true; }

    setLayer(hospitalsLayer, showHosp);
    setLayer(policeLayer, showPolice);
    setLayer(rescuersLayer, showResc);
    setLayer(survivorsLayer, showSurv);
  };

  // initial state
  window.applyRole('Survivor');
</script>
</body>
</html>
""";
    }

    public static void main(String[] args) {
        launch(args);
    }
}
