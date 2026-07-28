param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Password = $env:DEFAULT_PASSWORD
)
$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw "Set DEFAULT_PASSWORD or pass -Password before running the smoke test."
}

function Login([string]$Username) {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
        -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
}

$requester = Login "requester"
$approver = Login "approver"
$requesterHeaders = @{ Authorization = "Bearer $($requester.token)"; "X-Correlation-ID" = "smoke-$([guid]::NewGuid())" }
$approverHeaders = @{ Authorization = "Bearer $($approver.token)" }

$workflow = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/workflows" -Headers $requesterHeaders `
    -ContentType "application/json" -Body (@{
        title = "Automated smoke test"
        description = "Validates the final microservice workflow"
        requestType = "GENERAL"
        priority = "HIGH"
    } | ConvertTo-Json)

$tasks = @(Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/approvals/tasks" -Headers $approverHeaders)
if ($workflow.id -notin $tasks.id) { throw "Created workflow is missing from approval tasks" }

$decision = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/approvals/$($workflow.id)/approve" `
    -Headers $approverHeaders -ContentType "application/json" -Body '{"comment":"Smoke test approval"}'
if ($decision.decision -ne "APPROVED") { throw "Approval failed" }

$detail = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/workflows/$($workflow.id)" -Headers $requesterHeaders
if ($detail.status -ne "APPROVED") { throw "Workflow status did not update" }

$deadline = (Get-Date).AddSeconds(45)
do {
    Start-Sleep -Seconds 1
    $auditResponse = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/audit-logs?workflowId=$($workflow.id)" -Headers $requesterHeaders
    $audits = @($auditResponse)
} while ($audits.Count -lt 2 -and (Get-Date) -lt $deadline)
if ($audits.Count -lt 2) { throw "Outbox events were not delivered" }

[pscustomobject]@{
    workflowId = $workflow.id
    status = $detail.status
    auditActions = $audits.action -join ","
} | ConvertTo-Json
