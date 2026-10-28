import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const baseUrl = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const vus = Number.parseInt(__ENV.VUS || "20", 10);
const iterationsPerVu = Number.parseInt(__ENV.ITERATIONS_PER_VU || "25", 10);
const jobCount = vus * iterationsPerVu;
const maxWaitSeconds = Number.parseInt(__ENV.MAX_WAIT_SECONDS || "120", 10);

const claimLatency = new Trend("claim_latency_ms", true);
const completedJobs = new Counter("jobs_completed");
const failedJobs = new Counter("jobs_failed");

export const options = {
  scenarios: {
    worker_load: {
      executor: "per-vu-iterations",
      vus,
      iterations: iterationsPerVu,
      maxDuration: "30m",
    },
  },
  summaryTrendStats: ["avg", "med", "p(95)", "max"],
  thresholds: {
    jobs_failed: ["count==0"],
  },
};

export function setup() {
  const jobs = [];
  const headers = { "Content-Type": "application/json" };

  for (let index = 0; index < jobCount; index += 1) {
    const response = http.post(
      `${baseUrl}/api/jobs`,
      JSON.stringify({ type: "noop", maxAttempts: 1 }),
      { headers },
    );

    if (response.status !== 201) {
      throw new Error(
        `job submission ${index + 1}/${jobCount} returned ${response.status}: ${response.body}`,
      );
    }

    const job = response.json();
    jobs.push({ id: job.id, createdAt: job.createdAt });
  }

  return { jobs };
}

export default function (data) {
  const index = (__VU - 1) * iterationsPerVu + __ITER;
  const job = data.jobs[index];
  const deadline = Date.now() + maxWaitSeconds * 1000;
  let claimMeasured = false;

  while (Date.now() < deadline) {
    const response = http.get(`${baseUrl}/api/jobs/${job.id}`);
    if (!check(response, { "job lookup returned 200": (res) => res.status === 200 })) {
      sleep(0.1);
      continue;
    }

    const current = response.json();
    const attempts = current.attemptHistory || [];
    if (!claimMeasured && attempts.length > 0) {
      const startedAt = Date.parse(attempts[0].startedAt);
      const createdAt = Date.parse(job.createdAt);
      claimLatency.add(Math.max(0, startedAt - createdAt));
      claimMeasured = true;
    }

    if (current.status === "SUCCEEDED") {
      completedJobs.add(1);
      check(current, { "job completed successfully": (value) => value.status === "SUCCEEDED" });
      return;
    }

    if (["FAILED", "CANCELLED"].includes(current.status)) {
      failedJobs.add(1);
      check(current, { "job completed successfully": (value) => value.status === "SUCCEEDED" });
      return;
    }

    sleep(0.1);
  }

  failedJobs.add(1);
  check(false, { "job completed before timeout": (value) => value === true });
}

