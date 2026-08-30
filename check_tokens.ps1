$sa = Get-Content "app/src/main/res/raw/service_account.json" | ConvertFrom-Json
$projectId = $sa.project_id
$url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/chat_users"
$response = Invoke-RestMethod -Uri $url -Method Get
$response.documents | ForEach-Object {
    $mobile = $_.name -replace ".*/", ""
    $fcmToken = $_.fields.fcmToken.stringValue
    Write-Host "Mobile: $mobile, FCM Token: $fcmToken"
}
