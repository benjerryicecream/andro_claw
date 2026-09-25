import os

path = r'c:\Users\chase\andro_claw\app\src\main\java\com\androclaw\agent\agent\AgentLoop.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

old = '''                if (snapshot.isEmpty() && prefs.debugMode) {
                    screenshotBase64 = screenCapture.captureBase64(accessibilityService)
                }'''
new = '''                val service = ClawAccessibilityService.instance.value
                if (service != null && snapshot.isEmpty() && prefs.debugMode) {
                    screenshotBase64 = screenCapture.captureBase64(service)
                }'''
if old in content:
    content = content.replace(old, new)
    print("Fixed AgentLoop captureBase64 call")
else:
    print("Warning: could not find old block")

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
