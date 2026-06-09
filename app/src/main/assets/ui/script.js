(function() {
  'use strict';

  // ---- State ----
  let isFirstBoot = false;
  let isReady = false;
  let serverUrl = null;
  let terminalDataBuffer = '';
  let checkInterval = null;
  let currentStepIndex = 0;
  let waitingForCommandEcho = false;
  let commandSentTime = 0;
  let pipelineActive = false;

  // ---- DOM refs ----
  const bootLayer = document.getElementById('boot-layer');
  const serverLayer = document.getElementById('server-layer');
  const serverFrame = document.getElementById('server-frame');
  const progressFill = document.getElementById('progress-fill');
  const statusLabel = document.getElementById('status-label');
  const actionBtn = document.getElementById('action-btn');
  const fabClose = document.getElementById('fab-close');
  const brandingIcon = document.getElementById('branding-icon');

  // ---- Steps ----
  const FIRST_BOOT_STEPS = [
    { cmd: 'apk update', progress: 20, desc: 'Updating package manifests... (\u062A\u062D\u062F\u064A\u062B \u0627\u0644\u062D\u0632\u0645)' },
    { cmd: 'apk add nodejs npm git', progress: 45, desc: 'Installing Runtime environments... (\u062A\u062B\u0628\u064A\u062A \u0628\u064A\u0626\u0629 \u0627\u0644\u0646\u0648\u062F)' },
    { cmd: 'npm install -g opencode-ai@latest', progress: 75, desc: 'Configuring OpenCode core modules... (\u0625\u0639\u062F\u0627\u062F \u0645\u0644\u0641\u0627\u062A \u0627\u0644\u0630\u0643\u0627\u0621)' },
    { cmd: 'opencode serve', progress: 90, desc: 'Launching background server... (\u0628\u062F\u0621 \u062A\u0634\u063A\u064A\u0644 \u0627\u0644\u062E\u0627\u062F\u0645)' }
  ];

  const SUBSEQUENT_BOOT_STEPS = [
    { cmd: 'opencode serve', progress: 50, desc: 'Booting OpenCode Engine... (\u062A\u0634\u063A\u064A\u0644 \u0627\u0644\u0645\u062D\u0631\u0643)' }
  ];

  // ---- Update UI ----
  function updateProgress(percent, text) {
    if (progressFill) progressFill.style.width = percent + '%';
    if (text && statusLabel) statusLabel.textContent = text;
  }

  function setReady() {
    isReady = true;
    updateProgress(100, 'Ready! (\u062C\u0627\u0647\u0632 \u0644\u0644\u0639\u0645\u0644)');
    if (statusLabel) statusLabel.style.color = '#58A6FF';
    if (actionBtn) {
      actionBtn.disabled = false;
      actionBtn.className = 'ready';
      actionBtn.textContent = 'OpenCode';
    }
  }

  function showError(title, details) {
    if (checkInterval) {
      clearInterval(checkInterval);
      checkInterval = null;
    }
    pipelineActive = false;

    if (progressFill && progressFill.parentNode) {
      progressFill.parentNode.style.display = 'none';
    }
    if (brandingIcon) {
      brandingIcon.style.animation = 'none';
      brandingIcon.innerHTML =
        '<svg width="90" height="90" viewBox="0 0 24 24" fill="none" stroke="#FF4A4A" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
        '<circle cx="12" cy="12" r="10"></circle>' +
        '<line x1="12" y1="8" x2="12" y2="12"></line>' +
        '<line x1="12" y1="16" x2="12.01" y2="16"></line></svg>';
    }

    if (statusLabel) {
      statusLabel.innerHTML =
        '<div style="color:#FF4A4A;font-weight:700;font-size:16px;margin-bottom:8px;">' + title + '</div>' +
        '<div style="color:#8B949E;font-size:12px;background:#161B22;padding:10px;border-radius:8px;border:1px solid #30363D;text-align:left;word-break:break-all;max-height:120px;overflow-y:auto;font-family:monospace;">' + details + '</div>';
    }

    if (actionBtn) {
      actionBtn.textContent = 'Close';
      actionBtn.disabled = false;
      actionBtn.className = 'error';
      actionBtn.onclick = function() {
        window.location.reload();
      };
    }
  }

  // ---- Terminal Data Handler ----
  window.onTerminalData = function(chunk) {
    terminalDataBuffer += chunk;
    if (terminalDataBuffer.length > 50000) {
      terminalDataBuffer = terminalDataBuffer.slice(-25000);
    }

    // Check for the magic URL
    if (!isReady) {
      var urlMatch = terminalDataBuffer.match(/https?:\/\/127\.0\.0\.1:4096/);
      if (urlMatch) {
        serverUrl = urlMatch[0];
        setReady();
        stopPipeline();
        return;
      }
    }

    // Process pipeline steps
    if (pipelineActive && !waitingForCommandEcho && currentStepIndex < getCurrentSteps().length) {
      var isPromptReady = /root@localhost.*?[#$]\s*$|\/.*?[#$]\s*$/.test(terminalDataBuffer.trimEnd());
      var activeStep = getCurrentSteps()[currentStepIndex];
      if (isPromptReady) {
        updateProgress(activeStep.progress, activeStep.desc);
        AndroidTerminalBridge.sendPayload(activeStep.cmd);
        waitingForCommandEcho = true;
        commandSentTime = Date.now();
      }
    } else if (waitingForCommandEcho) {
      var promptReady = /root@localhost.*?[#$]\s*$|\/.*?[#$]\s*$/.test(terminalDataBuffer.trimEnd());
      if (promptReady && Date.now() - commandSentTime > 1800) {
        currentStepIndex++;
        waitingForCommandEcho = false;
        if (currentStepIndex >= getCurrentSteps().length) {
          stopPipeline();
        }
      }
    }
  };

  // ---- Install Progress ----
  window.onInstallProgress = function(percent, message) {
    updateProgress(percent, message);
  };

  // ---- Boot Ready ----
  window.onBootReady = function() {
    setReady();
    stopPipeline();
  };

  // ---- Boot Error ----
  window.onBootError = function(title, details) {
    showError(title, details);
  };

  // ---- Pipeline helpers ----
  function getCurrentSteps() {
    return isFirstBoot ? FIRST_BOOT_STEPS : SUBSEQUENT_BOOT_STEPS;
  }

  function stopPipeline() {
    pipelineActive = false;
    if (checkInterval) {
      clearInterval(checkInterval);
      checkInterval = null;
    }
  }

  // ---- Action Button ----
  actionBtn.addEventListener('click', function() {
    if (actionBtn.className === 'error') {
      window.location.reload();
      return;
    }
    if (!actionBtn.disabled && isReady) {
      navigateToServer();
    }
  });

  // ---- Navigation ----
  function navigateToServer() {
    bootLayer.style.display = 'none';
    serverLayer.style.display = 'flex';
    var targetUrl = serverUrl || 'http://127.0.0.1:4096/';
    serverFrame.src = targetUrl;
  }

  function navigateToDashboard() {
    serverLayer.style.display = 'none';
    bootLayer.style.display = 'flex';
    serverFrame.src = 'about:blank';

    // Reset for restart
    isReady = false;
    serverUrl = null;
    updateProgress(0, 'Server stopped. Click OpenCode to restart. (\u0627\u0644\u062E\u0627\u062F\u0645 \u0645\u062A\u0648\u0642\u0641)');
    if (statusLabel) statusLabel.style.color = '#C9D1D9';
    if (progressFill && progressFill.parentNode) {
      progressFill.parentNode.style.display = '';
    }
    if (brandingIcon) brandingIcon.style.animation = 'pulseBranding 2.5s infinite ease-in-out';

    if (actionBtn) {
      actionBtn.disabled = true;
      actionBtn.className = '';
      actionBtn.style.cssText = '';
      actionBtn.textContent = 'OpenCode';
    }

    // Send Ctrl+C to stop server, wait, then re-check
    AndroidTerminalBridge.sendCtrlC();
    setTimeout(function() {
      terminalDataBuffer = '';
      currentStepIndex = 0;
      waitingForCommandEcho = false;
      pipelineActive = true;
      // Start subsequent boot pipeline
      startPipeline();
    }, 1200);
  }

  // ---- FAB close ----
  fabClose.addEventListener('click', navigateToDashboard);

  // ---- Back to dashboard from native side ----
  window.onReturnToDashboard = navigateToDashboard;

  // ---- Start Pipeline ----
  function startPipeline() {
    if (checkInterval) {
      clearInterval(checkInterval);
      checkInterval = null;
    }

    pipelineActive = true;
    currentStepIndex = 0;
    waitingForCommandEcho = false;

    checkInterval = setInterval(function() {
      if (!pipelineActive) return;

      // Check buffer for URL pattern
      if (!isReady && terminalDataBuffer.indexOf('http://127.0.0.1:4096') !== -1) {
        serverUrl = 'http://127.0.0.1:4096';
        setReady();
        stopPipeline();
        return;
      }

      var steps = getCurrentSteps();
      if (currentStepIndex < steps.length) {
        var isPromptReady = /root@localhost.*?[#$]\s*$|\/.*?[#$]\s*$/.test(terminalDataBuffer.trimEnd());
        var activeStep = steps[currentStepIndex];

        if (isPromptReady && !waitingForCommandEcho) {
          updateProgress(activeStep.progress, activeStep.desc);
          AndroidTerminalBridge.sendPayload(activeStep.cmd);
          waitingForCommandEcho = true;
          commandSentTime = Date.now();
        } else if (waitingForCommandEcho) {
          if (Date.now() - commandSentTime > 1800 && isPromptReady) {
            currentStepIndex++;
            waitingForCommandEcho = false;
          }
        }
      }
    }, 700);
  }

  // ---- Init ----
  window.addEventListener('DOMContentLoaded', function() {
    // Determine boot type
    try {
      isFirstBoot = AndroidTerminalBridge.isFirstBoot();
    } catch(e) {
      isFirstBoot = true;
    }

    updateProgress(5, isFirstBoot
      ? 'Preparing environment... (\u062C\u0627\u0631\u064A \u062A\u0647\u064A\u0626\u0629 \u0627\u0644\u0628\u064A\u0626\u0629)'
      : 'Booting OpenCode Engine... (\u062C\u0627\u0631\u064A \u062A\u0634\u063A\u064A\u0644 \u0627\u0644\u0645\u062D\u0631\u0643)'
    );

    // Start installation check
    try {
      AndroidTerminalBridge.checkInstallStatus();
    } catch(e) {
      // Fall back if bridge fails
      setTimeout(startPipeline, 1000);
    }

    // Set a timeout fallback: if no progress after 30s, start pipeline anyway
    setTimeout(function() {
      if (!isReady && !pipelineActive) {
        startPipeline();
      }
    }, 30000);
  });

  // Public: allow native code to trigger pipeline start
  window.startTerminalPipeline = startPipeline;

})();
