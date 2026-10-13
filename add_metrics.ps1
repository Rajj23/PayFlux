$services = @("api-gateway", "notification-service", "reward-service", "transaction-service", "user-service", "wallet-service")

$dependencyXml = @"
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
		<dependency>
			<groupId>io.micrometer</groupId>
			<artifactId>micrometer-registry-prometheus</artifactId>
			<scope>runtime</scope>
		</dependency>
"@

$ymlConfig = @"

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      probes:
        enabled: true
"@

$propConfig = @"

management.endpoints.web.exposure.include=health,prometheus
management.endpoint.health.probes.enabled=true
"@

foreach ($service in $services) {
    # 1. Update POM
    $pomPath = "d:\code\project\Paypal-Clone\$service\pom.xml"
    if (Test-Path $pomPath) {
        $content = Get-Content $pomPath -Raw
        if ($content -notmatch "spring-boot-starter-actuator") {
            $content = $content -replace "(?s)(<dependencies>\s*)", "`$1$dependencyXml`r`n"
            Set-Content -Path $pomPath -Value $content -Encoding UTF8
            Write-Host "Updated $pomPath"
        }
    }

    # 2. Update Application Config
    $ymlPath = "d:\code\project\Paypal-Clone\$service\src\main\resources\application.yml"
    $propPath = "d:\code\project\Paypal-Clone\$service\src\main\resources\application.properties"
    
    if (Test-Path $ymlPath) {
        $content = Get-Content $ymlPath -Raw
        if ($content -notmatch "prometheus") {
            Add-Content -Path $ymlPath -Value $ymlConfig -Encoding UTF8
            Write-Host "Updated $ymlPath"
        }
    } elseif (Test-Path $propPath) {
        $content = Get-Content $propPath -Raw
        if ($content -notmatch "prometheus") {
            Add-Content -Path $propPath -Value $propConfig -Encoding UTF8
            Write-Host "Updated $propPath"
        }
    }
}
