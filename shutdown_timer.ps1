Start-Sleep -Seconds 120
Add-Type -AssemblyName System.Windows.Forms
$wshell = New-Object -ComObject Wscript.Shell
$result = $wshell.Popup("আপনার কাজ শেষ হয়েছে। ৪ সেকেন্ডের মধ্যে পিসি শাটডাউন হতে যাচ্ছে। আপনি কি বাতিল করতে চান?", 4, "Auto Shutdown", 1 + 48)
if ($result -ne 2) {
    shutdown -s -t 0
} else {
    $wshell.Popup("শাটডাউন বাতিল করা হয়েছে।", 3, "Cancelled", 64)
}
