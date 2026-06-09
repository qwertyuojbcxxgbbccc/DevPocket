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
  const bootLayer    = document.getElementById('boot-layer');
  const serverLayer  = document.getElementById('server-layer');
  const serverFrame  = document.getElementById('server-frame');
  const progressFill = document.getElementById('progress-fill');
  const statusLabel  = document.getElementById('status-label');
  const actionBtn    = document.getElementById('action-btn');
  const fabClose     = document.getElementById('fab-close');
  const brandingIcon = document.getElementById('branding-icon');

  // ---- Steps ----
  // NOTE: First-boot installation is now fully handled by Java (TerminalService).
  // These steps only run for subsequent boots to launch the server.
  const SUBSEQUENT_BOOT_STEPS = [
    { cmd: 'opencode serve', progress: 50,
      desc: 'Booting OpenCode Engine... (\u062a\u0634\u063a\u064a\u0644 \u0627\u0644\u0645\u062d\u0631\u0643)' }
  ];

  function getCurrentSteps() {
    // First-boot: Java handles all installation steps and calls onInstallProgress/onBootReady.
    // JS pipeline only needed for subsequent boots (just launching the server).
    return SUBSEQUENT_BOOT_STEPS;
  }

  // ---- Update UI ----
  function updateProgress(percent, text) {
    if (progressFill) progressFill.style.width = percent + '%';
    if (text && statusLabel) statusLabel.textContent = text;
  }

  function setReady() {
    if (isReady) return; // guard against double-call
    isReady = true;
    stopPipeline();
    updateProgress(100, 'Ready! (\u062c\u0627\u0647\u0632 \u0644\u0644\u0639\u0645\u0644)');
    if (statusLabel) statusLabel.style.color = '#58A6FF';
    if (actionBtn) {
      actionBtn.disabled = false;
      actionBtn.className = 'ready';
      actionBtn.textContent = 'OpenCode';
    }
  }

  function showError(title, details) {
    stopPipeline();
    if (progressFill && progressFill.parentNode) {
      progressFill.parentNode.style.display = 'none';
    }
    if (brandingIcon) {
      brandingIcon.style.animation = 'none';
      brandingIcon.innerHTML =
        '<svg width="90" height="90" viewBox="0 0 24 24" fill="none" stroke="#FF4A4A"' +
        ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
        '<circle cx="12" cy="12" r="10"></circle>' +
        '<line x1="12" y1="8" x2="12" y2="12"></line>' +
        '<line x1="12" y1="16" x2="12.01" y2="16"></line></svg>';
    }
    if (statusLabel) {
      statusLabel.innerHTML =
        '<div style="color:#FF4A4A;font-weight:700;font-size:16px;margin-bottom:8px;">' + title + '</div>' +
        '<div style="color:#8B949E;font-size:12px;background:#161B22;padding:10px;border-radius:8px;' +
        'border:1px solid #30363D;text-align:left;word-break:break-all;max-height:120px;' +
        'overflow-y:auto;font-family:monospace;">' + details + '</div>';
    }
    if (actionBtn) {
      actionBtn.textContent = 'Retry';
      actionBtn.disabled = false;
      actionBtn.className = 'error';
      actionBtn.onclick = function() { window.location.reload(); };
    }
  }

  // ---- Terminal Data Handler (called from Java) ----
  window.onTerminalData = function(chunk) {
    terminalDataBuffer += chunk;
    if (terminalDataBuffer.length > 50000) {
      terminalDataBuffer = terminalDataBuffer.slice(-25000);
    }

    if (!isReady && terminalDataBuffer.indexOf('http://127.0.0.1:4096') !== -1) {
      serverUrl = 'http://127.0.0.1:4096';
      setReady();
      return;
    }

    // Only drive the JS pipeline for subsequent boots
    if (!pipelineActive || isFirstBoot) return;

    var steps = getCurrentSteps();
    var isPromptReady = /root@\S+.*?[#$]\s*$|\/.*?[#$]\s*$/.test(terminalDataBuffer.trimEnd());

    if (!waitingForCommandEcho && currentStepIndex < steps.length) {
      if (isPromptReady) {
        var step = steps[currentStepIndex];
        updateProgress(step.progress, step.desc);
        AndroidTerminalBridge.sendPayload(step.cmd);
        waitingForCommandEcho = true;
        commandSentTime = Date.now();
      }
    } else if (waitingForCommandEcho) {
      if (isPromptReady && Date.now() - commandSentTime > 1800) {
        currentStepIndex++;
        waitingForCommandEcho = false;
        if (currentStepIndex >= steps.length) stopPipeline();
      }
    }
  };

  // ---- Called from Java during first-boot installation ----
  window.onInstallProgress = function(percent, message) {
    updateProgress(percent, message);
  };

  // ---- Called from Java when server URL is detected in output ----
  window.onBootReady = function() {
    setReady();
  };

  // ---- Called from Java on fatal error ----
  window.onBootError = function(title, details) {
    showError(title, details);
  };

  // ---- Pipeline helpers ----
  function stopPipeline() {
    pipelineActive = false;
    if (checkInterval) { clearInterval(checkInterval); checkInterval = null; }
  }

  // Polling fallback — only needed for subsequent boot (first-boot driven by Java callbacks)
  function startSubsequentBootPipeline() {
    stopPipeline();
    pipelineActive = true;
    currentStepIndex = 0;
    waitingForCommandEcho = false;

    checkInterval = setInterval(function() {
      if (!pipelineActive) return;

      if (!isReady && terminalDataBuffer.indexOf('http://127.0.0.1:4096') !== -1) {
        serverUrl = 'http://127.0.0.1:4096';
        setReady();
        return;
      }

      var steps = getCurrentSteps();
      if (currentStepIndex >= steps.length) { stopPipeline(); return; }

      var isPromptReady = /root@\S+.*?[#$]\s*$|\/.*?[#$]\s*$/.test(terminalDataBuffer.trimEnd());
      var step = steps[currentStepIndex];

      if (!waitingForCommandEcho && isPromptReady) {
        updateProgress(step.progress, step.desc);
        AndroidTerminalBridge.sendPayload(step.cmd);
        waitingForCommandEcho = true;
        commandSentTime = Date.now();
      } else if (waitingForCommandEcho && isPromptReady && Date.now() - commandSentTime > 1800) {
        currentStepIndex++;
        waitingForCommandEcho = false;
      }
    }, 700);
  }

  // ---- Action Button ----
  actionBtn.addEventListener('click', function() {
    if (actionBtn.className === 'error') { window.location.reload(); return; }
    if (!actionBtn.disabled && isReady) navigateToServer();
  });

  // ---- Navigation ----
  function navigateToServer() {
    bootLayer.style.display  = 'none';
    serverLayer.style.display = 'flex';
    serverFrame.src = serverUrl || 'http://127.0.0.1:4096/';
  }

  function navigateToDashboard() {
    serverLayer.style.display = 'none';
    bootLayer.style.display   = 'flex';
    serverFrame.src = 'about:blank';

    isReady = false;
    serverUrl = null;
    terminalDataBuffer = '';
    currentStepIndex = 0;
    waitingForCommandEcho = false;

    updateProgress(0, 'Server stopped. Click OpenCode to restart. (\u0627\u0644\u062e\u0627\u062f\u0645 \u0645\u062a\u0648\u0642\u0641)');
    if (statusLabel) statusLabel.style.color = '#C9D1D9';
    if (progressFill && progressFill.parentNode) progressFill.parentNode.style.display = '';
    if (brandingIcon) brandingIcon.style.animation = 'pulseBranding 2.5s infinite ease-in-out';

    if (actionBtn) {
      actionBtn.disabled = true;
      actionBtn.className = '';
      actionBtn.style.cssText = '';
      actionBtn.textContent = 'OpenCode';
      // Restore normal click handler
      actionBtn.onclick = null;
    }

    // Send Ctrl+C then restart the pipeline
    try { AndroidTerminalBridge.sendCtrlC(); } catch(e) {}
    setTimeout(function() {
      updateProgress(10, 'Restarting engine... (\u0625\u0639\u0627\u062f\u0629 \u062a\u0634\u063a\u064a\u0644 \u0627\u0644\u0645\u062d\u0631\u0643)');
      startSubsequentBootPipeline();
    }, 1200);
  }

  // ---- FAB close ----
  fabClose.addEventListener('click', navigateToDashboard);

  // ---- Back to dashboard from native side ----
  window.onReturnToDashboard = navigateToDashboard;

  // Public hook
  window.startTerminalPipeline = startSubsequentBootPipeline;

  // ---- Init ----
  // FIX #4: Use 'load' instead of 'DOMContentLoaded' to guarantee
  // the JavascriptInterface is fully registered before we call into it
  window.addEventListener('load', function() {
    try {
      isFirstBoot = AndroidTerminalBridge.isFirstBoot();
    } catch(e) {
      isFirstBoot = true;
    }

    updateProgress(5, isFirstBoot
      ? 'Preparing environment... (\u062c\u0627\u0631\u064a \u062a\u0647\u064a\u0626\u0629 \u0627\u0644\u0628\u064a\u0626\u0629)'
      : 'Booting OpenCode Engine... (\u062c\u0627\u0631\u064a \u062a\u0634\u063a\u064a\u0644 \u0627\u0644\u0645\u062d\u0631\u0643)'
    );

    try {
      AndroidTerminalBridge.checkInstallStatus();
    } catch(e) {
      console.error('Bridge error:', e);
      // Fallback: show error so user knows something is wrong
      showError('Bridge Error', 'AndroidTerminalBridge not available: ' + e);
    }

    // Safety net: if nothing happens in 45s, show a diagnostic error
    setTimeout(function() {
      if (!isReady && !pipelineActive) {
        showError(
          'Timeout (\u062a\u062c\u0645\u062f)',
          'No terminal output received after 45s. ' +
          'Check logcat for TerminalService errors.'
        );
      }
    }, 45000);
  });

})();
