import { createFileRoute } from "@tanstack/react-router";

type UpstreamScore = {
  team?: string;
  inning?: string;
  r?: number;
  w?: number;
  o?: number;
};

type UpstreamMatch = {
  id?: string;
  name?: string;
  matchType?: string;
  status?: string;
  venue?: string;
  date?: string;
  teams?: string[];
  score?: UpstreamScore[];
};

export const Route = createFileRoute("/api/cricket/current")({
  server: {
    handlers: {
      GET: async () => {
        const apiKey = process.env.CRICAPI_API_KEY;
        if (!apiKey) {
          return Response.json(
            { error: "Cricket score service is not configured" },
            { status: 503, headers: { "Cache-Control": "no-store" } },
          );
        }

        try {
          const url = new URL("https://api.cricapi.com/v1/currentMatches");
          url.searchParams.set("apikey", apiKey);
          url.searchParams.set("offset", "0");

          const upstream = await fetch(url, {
            signal: AbortSignal.timeout(8_000),
            cache: "no-store",
          });
          if (!upstream.ok) {
            return Response.json(
              { error: "Cricket score provider is unavailable" },
              { status: 502, headers: { "Cache-Control": "no-store" } },
            );
          }

          const payload = (await upstream.json()) as {
            status?: string;
            reason?: string;
            data?: UpstreamMatch[];
          };
          if (payload.status === "failure" && payload.reason === "Invalid API Key") {
            return Response.json(
              { error: "CricAPI rejected the configured server key" },
              { status: 502, headers: { "Cache-Control": "no-store" } },
            );
          }
          if (payload.status !== "success" || !Array.isArray(payload.data)) {
            return Response.json(
              { error: "Cricket score provider returned an invalid response" },
              { status: 502, headers: { "Cache-Control": "no-store" } },
            );
          }

          const matches = payload.data.map((match) => ({
            id: match.id ?? match.name ?? "",
            name: match.name ?? "Cricket match",
            matchType: match.matchType ?? "",
            status: match.status ?? "Live",
            venue: match.venue ?? "",
            date: match.date ?? "",
            teams: Array.isArray(match.teams) ? match.teams.slice(0, 2) : [],
            score: Array.isArray(match.score)
              ? match.score.slice(0, 4).map((score) => ({
                  team: score.team ?? "",
                  inning: score.inning ?? "",
                  runs: Number.isFinite(score.r) ? score.r : null,
                  wickets: Number.isFinite(score.w) ? score.w : null,
                  overs: Number.isFinite(score.o) ? score.o : null,
                }))
              : [],
          }));

          return Response.json(
            { matches, updatedAt: new Date().toISOString() },
            {
              headers: {
                "Cache-Control": "public, s-maxage=60, stale-while-revalidate=30",
              },
            },
          );
        } catch {
          return Response.json(
            { error: "Unable to retrieve live cricket scores" },
            { status: 502, headers: { "Cache-Control": "no-store" } },
          );
        }
      },
    },
  },
});
