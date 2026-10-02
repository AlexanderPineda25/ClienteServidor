@echo off
setlocal
cd /d "%~dp0"

if not exist "client-app.jar" (
    echo Missing client-app.jar. Copy the complete Java client distribution first.
    pause
    exit /b 1
)
if not exist "client.properties" (
    echo Missing client.properties. Configure host and puerto before starting.
    pause
    exit /b 1
)

java --module-path "javafx" --add-modules javafx.controls,javafx.fxml -cp "client-app.jar;lib/*" universidad.mensajeria.cliente.ClienteMain
if errorlevel 1 pause
