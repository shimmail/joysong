param(
    [int]$Port = 18080,
    [ValidateSet("valid", "invalid-json", "illegal-enum", "timeout", "safety-downgrade")]
    [string]$Scenario = "valid"
)

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Write-Host "Mock LLM gateway: http://127.0.0.1:$Port/v1 ($Scenario). Ctrl+C to stop."

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $reader = [System.IO.StreamReader]::new($context.Request.InputStream, $context.Request.ContentEncoding)
        $requestBody = $reader.ReadToEnd()
        $reader.Dispose()
        $isParser = $requestBody.Contains("Classify one medical-aesthetic chat request")

        if ($isParser -and $Scenario -eq "timeout") { Start-Sleep -Seconds 10 }
        $content = if (-not $isParser) {
            "这是模拟网关生成的简短回答。"
        } elseif ($Scenario -eq "invalid-json") {
            "The user probably needs a plan."
        } elseif ($Scenario -eq "illegal-enum") {
            '{"intent":"BUY_NOW","queryTarget":"BEAUTY_HOSPITAL","keywords":[]}'
        } elseif ($Scenario -eq "safety-downgrade") {
            '{"intent":"CATALOG_QA","queryTarget":"PROJECT","keywords":["热玛吉"]}'
        } else {
            '{"intent":"PLANNING","queryTarget":"PROJECT","keywords":["抗衰紧致","低恢复期"]}'
        }

        $payload = @{
            choices = @(@{ message = @{ role = "assistant"; content = $content } })
            usage = @{ prompt_tokens = 20; completion_tokens = 20 }
        } | ConvertTo-Json -Depth 8 -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
        $context.Response.StatusCode = 200
        $context.Response.ContentType = "application/json; charset=utf-8"
        $context.Response.ContentLength64 = $bytes.Length
        $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $context.Response.Close()
    }
} finally {
    $listener.Stop()
    $listener.Close()
}
