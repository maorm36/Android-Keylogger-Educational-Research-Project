// Import Express framework for creating the server
const express = require('express');

// Import zlib framework for zipping
const zlib = require('zlib');

require('multer');
// Import path module for working with file paths
const path = require('path');

// Import fs (file system) module for file operations
const fs = require('fs');

// Create an Express application instance
const app = express();

// Enable JSON parsing for incoming request bodies
app.use(express.json({limit: "10mb", type: ["application/json", "application/*+json"]}));
app.use(express.urlencoded({extended: true}));

// the port of the sever is 3000
const PORT = 3000;

// === AUTHENTICATION ===

// the server key must match to the key on android app
const API_KEY = 'YOUR-API-KEY';

function authenticateApiKey(req, res, next) {
    const apiKey = req.headers['x-api-key'];
    if (apiKey === API_KEY) {
        next();
    } else {
        res.status(401).json({error: 'Invalid API key'});
    }
}

// Apply auth to all /api routes
app.use('/api', authenticateApiKey);
// === END AUTH ===

// Define the path to the JSON file that will store event data
const dataFile = path.join(__dirname, 'public/userEvents.json');

// Function to load events from JSON file
function loadEvents() {
    // Check if data file exists
    if (fs.existsSync(dataFile)) {
        // Read file contents as string
        const data = fs.readFileSync(dataFile, 'utf-8');
        // Parse JSON string to array and return
        return JSON.parse(data);
    }
    // Return empty array if file doesn't exist
    return [];
}

// Function to save events to JSON file
function saveEvents() {
    // Convert events array to formatted JSON string and write to file
    fs.writeFileSync(dataFile, JSON.stringify(events, null, 2), 'utf-8');
    // Log save confirmation
    console.log(`Saved ${events.length} events to ${dataFile}`);
}

// Load existing events from file on server start
let events = loadEvents();

// Log how many events were loaded
console.log(`Loaded ${events.length} events from file`);


// --------------------------------
//          HTTP Endpoints
// --------------------------------


app.use("/public", express.static(path.join(__dirname, "public")));

app.post("/api/collect", async (req, res) => {
    try {
        console.log("Content-Type:", req.headers["content-type"]);
        console.log("Body keys:", Object.keys(req.body || {}));
        console.log("Raw body:", req.body);

        const {event_type, timestamp, session_id, device_id, metadata} = req.body || {};

        // Log metadata sizes
        console.log("metadata present:", !!metadata);
        console.log("metadata b64 length:", metadata?.length);

        let decoded = null;

        if (metadata) {
            const buf = Buffer.from(metadata, "base64");
            console.log("metadata buffer length:", buf.length);
            console.log("metadata first bytes (hex):", buf.slice(0, 8).toString("hex"));

            // If gzip (1f 8b), decompress
            let rawBuf = buf;
            if (buf.length >= 2 && buf[0] === 0x1f && buf[1] === 0x8b) {
                rawBuf = zlib.gunzipSync(buf);
                console.log("after gunzip length:", rawBuf.length);
            }

            const decodedStr = rawBuf.toString("utf8");
            console.log("decoded first 300 chars:", decodedStr.slice(0, 300));

            try {
                decoded = JSON.parse(decodedStr);
            } catch {
                decoded = decodedStr; // not JSON
            }
        }

        const newEvent = {
            event_type,
            timestamp,
            session_id,
            device_id,
            metadata_base64: metadata,
            metadata_decoded: decoded,
        };

        events.push(newEvent);
        saveEvents();

        return res.status(201).json({newEvent, success: true, received: events.length, session_id});
    } catch (error) {
        console.error("Error processing data:", error);
        return res.status(500).json({error: "Internal server error"});
    }
});


// GET /api/events - Return all events
app.get('/api/events', (req, res) => {
    // Send the entire events array as JSON response
    res.json(events);
});


// GET /api/health - Health check
app.get('/api/health', (req, res) => {
    res.json({status: 'ok', timestamp: new Date().toISOString()});
});


// GET /api/backup - Create a backup of the data
app.get('/api/backup', (req, res) => {
    // Create backup filename with timestamp
    const backupFile = path.join(__dirname, `events-backup-${Date.now()}.json`);
    // Write current events data to a backup file
    fs.writeFileSync(backupFile, JSON.stringify(events, null, 2), 'utf-8');
    // Return success message with backup path
    res.json({message: 'Backup created', file: backupFile});
});


// Start the server on port 3000
app.listen(PORT, () => {
    // Log message when server starts successfully
    console.log(`event server running on http://localhost:${PORT}`);
    // Log data file location
    console.log(`Data file: ${dataFile}`);
});