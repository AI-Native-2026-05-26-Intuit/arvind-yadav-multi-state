// Static, dependency-free HTTP health probe for the distroless runtime image.
//
// Distroless has no shell, curl or wget, so the Docker HEALTHCHECK cannot be a
// shell command. This ~2 MB (stripped, static) binary GETs the actuator
// readiness endpoint and maps the result to an exit code:
//
//	exit 0  -> 2xx/3xx response  (healthy)
//	exit 1  -> anything else, or the request failed (unhealthy)
//
// Target URL defaults to the public aggregate /actuator/health endpoint (which
// reports the liveness + readiness groups). The /actuator/health/{liveness,
// readiness} sub-paths are secured by the resource server and return 401, so
// the aggregate is the correct unauthenticated container-probe target.
// Override with HEALTHCHECK_URL if a deployment exposes the sub-paths
// unauthenticated.
package main

import (
	"net/http"
	"os"
	"time"
)

func main() {
	url := os.Getenv("HEALTHCHECK_URL")
	if url == "" {
		url = "http://127.0.0.1:8080/actuator/health"
	}

	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(url)
	if err != nil {
		os.Exit(1)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 200 && resp.StatusCode < 400 {
		os.Exit(0)
	}
	os.Exit(1)
}
