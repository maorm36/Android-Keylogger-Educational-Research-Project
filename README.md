# Android Keylogger - Educational Research Project

⚠️ **FOR EDUCATIONAL PURPOSES ONLY** ⚠️

This project demonstrates advanced mobile security vulnerabilities and attack vectors on Android.  
It is designed for:
- Academic research and education
- Security awareness training
- Understanding attack methodologies
- Defensive security research

## ⚠️ Legal & Ethical Disclaimer

**CRITICAL WARNING:**
- Using this software on devices you don't own is **ILLEGAL**
- Deploying without explicit consent is **UNETHICAL** and may violate laws
- This is for **ACADEMIC USE ONLY** in controlled environments
- The developers assume **NO RESPONSIBILITY** for misuse
- Users must comply with all applicable laws and regulations

## 🎯 Project Overview

This is a comprehensive keylogger implementation demonstrating:
- AccessibilityService exploitation
- Machine Learning for sensitive data detection
- Extensive infrastructure for an always running service  
- Sending data to the attacker machine  
- Android version adaptation (14, 15, 16)

## 📷 Images from the project

**The Android App:**<br>
<img width="300" height="500" alt="image" src="https://github.com/user-attachments/assets/e2a3b706-5018-4f87-b072-17859475b182" /><br><br>
  
**The Dashboard:**<br>
<img width="860" height="857" alt="image" src="https://github.com/user-attachments/assets/3ebf2d62-e84c-4d84-b141-37c342d09761" /><br><br>
  
**The Attacked Device Screen Displayer:**<br>
<img width="1600" height="769" alt="image" src="https://github.com/user-attachments/assets/68c144db-1fe7-4cc4-aa2a-e821822ef631" /><br><br>  
  

## 🏗️ Architecture

```
┌─────────────────────────────────────────┐
│         Data Collection Layer           │
│  ┌─────────────────────────────────┐    │
│  │      Accessibility Service      │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│     Processing & Analysis Layer         │
│  ┌─────────────────────────────────┐    │
│  │       TFLite ML Classifier      │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│     Storage & Exfiltration Layer        │
│  ┌──────────────┬──────────────────┐    │
│  │ Encrypted DB │  Remote Server   │    │
│  │ (SQLCipher)  │                  │    │
│  │              │                  │    │
│  └──────────────┴──────────────────┘    │
└─────────────────────────────────────────┘
```

## 🚀 Features

### Core Capabilities
- ✅ Keystroke capture via AccessibilityService
- ✅ Visual tree capture (screenshot alternative)
- ✅ Encrypted local storage (SQLCipher)
- ✅ Machine Learning sensitive data detection

### Security & Evasion
- ✅ Anti-debugging detection
- ✅ Emulator detection
- ✅ Root detection
- ✅ Frida/Xposed detection
- ✅ Integrity verification
- ✅ ProGuard obfuscation

### Exfiltration Method
- ✅ HTTP piggybacking (disguised as analytics)

### All time running mechanisms for keeping covert services alive
- ✅ START_STICKY 
- ✅ Service Restart On onDestroy() Or onTaskRemoved()
- ✅ Exact Alarms For Surviving Doze Mode
- ✅ BLE Scanner That Runs At The Android OS Level
- ✅ Paired Bluetooth Events Receiver
- ✅ Boot Completed Receiver
- ✅ WorkManager Periodic That Runs Every 15-30 Minutes As A Failsafe

### ML Detection
- ✅ Password detection
- ✅ Credit card detection (with Luhn validation)
- ✅ PII detection: email & phone numbers
- ✅ TensorFlow Lite integration
- ✅ Entropy analysis

## 📦 Project Structure

```
app/
├── src/main/
│   ├── java/com/android/myapp/
│   │ 
│   │   ├── ble/
│   │   │   └── PassiveBleScanner.kt
│   │   ├── capture/
│   │   │   ├── EventProcessor.kt
│   │   │   └── VisualTreeCapture.kt
│   │   ├── core/
│   │   │   ├── Batteryconfig.kt
│   │   │   ├── ServiceOrchestrator.kt
│   │   │   └── VersionManager.kt
│   │   ├── data/
│   │   │   ├── repository/
│   │   │   │   └── KeystrokeRepository.kt
│   │   │   ├── CapturedEvent.kt
│   │   │   └── KeyloggerDatabase.kt
│   │   ├── exfiltration/
│   │   │   ├── ExfiltrationManager.kt
│   │   │   └── ExfiltrationWorker.kt
│   │   ├── ml/
│   │   │   └── SensitiveDataDetector.kt
│   │   ├── receiver/
│   │   │   ├── AlarmWakeReceiver.kt
│   │   │   ├── BleScanReceiver.kt
│   │   │   ├── BluetoothEventReceiver.kt
│   │   │   ├── BootCompletedReceiver.kt
│   │   │   ├── SecretCodeReceiver.kt
│   │   │   └── ServiceRestartReceiver.kt
│   │   ├── security/
│   │   │   ├── AntiAnalysis.kt
│   │   │   └── StealthManager.kt
│   │   ├── service/
│   │   │   ├── KeyloggerAccessibilityService.kt
│   │   │   └── PersistenceService.kt
│   │   ├── ui/
│   │   │   ├── ConsentActivity.kt
│   │   │   └── DashboardActivity.kt
│   │   ├── worker/
│   │   │   └── ServiceCheckWorker.kt
│   │   ├── readme.md
│   │   └── KeyloggerApplication.kt
│   │
│   ├── AndroidManifest.xml
│   │
│   ├── assets/
│   │   └── sensitive_data_detector.tflite
│   │   
│   └── res/
│       ├── drawable/
│       │   ├── ic_launcher_background.xml
│       │   ├── ic_launcher_foreground.xml
│       │   ├── icon_hacker.png
│       │   └── rv_border.xml
│       │        
│       ├── layout/
│       │   └── activity_dashboard.xml
│       │  
│       ├── values/
│       │   ├── colors.xml
│       │   ├── strings.png
│       │   └── themes.xml
│       │
│       └── xml/
│           ├── accessibility_service_config.xml
│           ├── backup_rules.xml
│           ├── data_extraction_rules.xml
│           └── network_security_config.xml
│
├── build.gradle.kts
└── proguard-rules.pro


keyloggerServer/
├── public/
│   ├── json_viewer_layout.html
│   └── web_dashboard.html
├── app.js
├── keyloggerServer.iml
├── package.json
└── package-lock.json


ML_For_Keylogger/
├── formatted_credit_cards.txt
├── formatted_phone_il.txt
├── formatted_emails.txt
├── formatted_passwords.txt
├── normal_data.txt
└── model.py
```

## 🛠 Prerequisites

- Android Studio otter or later
- Android device with API 34+ (Android 14.0+)
- Node.js 16+ (for server)
- Python 3.8+ (for ML training)

## Android App Setup

1. **Clone each part of the keylogger project (android, nodejs, python to your matching IDEs**

2. **Configure API key**
```kotlin
private val apiKey = "your-secret-api-key"
private val serverUrl = "https://your-server.com"
```

3. **Build the app**

4. **Install on android device**

## Server Setup

1. **Install the node dependencies**

2. **Configure API key in app.js**
```javascript
const API_KEY = 'your-secret-api-key';
```

3. **Run server (app.js)**
```bash
node app.js
```

4. **Open dashboard on browser**
```bash
dashboardURL: "https://your-server.com/public/web_dashboard.html"
```

## 📱 Usage

### First Launch

1. Launch app - consent dialog will appear
2. Read and accept the educational disclaimer
3. Enable Accessibility Service:
   - Settings → Accessibility → System Service → Enable
4. Return to the app, and enable permissions
5. On exit, the app will hide from launcher

### Monitoring

- The dashboard shows real-time captured events
- Sensitive data is marked

### Remote Monitoring

- Access web dashboard at `http://your-server-url/public/web_dashboard.html`
- View all captured data
- Filter by event type

## 🔬 Research Applications

### Security Research
- Study AccessibilityService vulnerabilities
- Analyze data leakage vectors
- Test security controls effectiveness

### Defensive Security
- Understand attacker methodologies
- Develop detection mechanisms
- Train security awareness

### Academic Projects
- Mobile security coursework
- Conference presentations

## 🛡️ Detection & Mitigation

### How to Detect This Keylogger

1. **Check Accessibility Services**
   - Settings → Accessibility → Look for unknown services

2. **Monitor Network Traffic**
   - Look for unusual DNS queries or HTTP traffic

3. **Check Running Services**
   - Check installed apps for unknown apps

### How to Protect Against It

1. **User Education**: Be cautious enabling Accessibility Services
2. **Regular Audits**: Review enabled accessibility services
3. **Network Monitoring**: Detect unusual traffic patterns
4. **App Vetting**: Only install from trusted sources
5. **OS Updates**: Keep Android updated (newer versions have better protections)

## 📚 References

- [Android Accessibility Service Documentation](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android Security Best Practices](https://developer.android.com/topic/security/best-practices)
- [OWASP Mobile Security Testing Guide](https://owasp.org/www-project-mobile-security-testing-guide/)

## 📄 License

This project is licensed under the MIT License for **EDUCATIONAL PURPOSES ONLY**.


## ⚖️ Responsible Disclosure

If you discover vulnerabilities in this code or real-world applications:

1. **DO NOT** exploit them maliciously
2. Report to affected parties privately
3. Follow responsible disclosure guidelines
4. Allow reasonable time for fixes

## 👥 Credits

Developed for academic research in mobile security.

**Remember**: With great power comes great responsibility.  
Use this knowledge ethically.

---

Made with ❤️ by Maor Mordo
