"""Local HTTP fixtures for SCRUM-22; not facility/booking production APIs."""

import json
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlsplit(self.path)
        parts = url.path.split("/")
        try:
            day = date.fromisoformat(parse_qs(url.query)["date"][0]).isoformat()
            if parts[:4] != ["", "api", "internal", "courts"] or len(parts) != 6:
                return self.reply(404, {})
            court = int(parts[4])
            if court != 1:
                return self.reply(404, {})
            if parts[5] == "slot-schedule":
                slots = []
                for index in range(48):
                    start, end = index * 30, (index + 1) * 30 % 1440
                    slots.append({
                        "slotId": index + 1,
                        "startTime": f"{start // 60:02}:{start % 60:02}:00",
                        "endTime": f"{end // 60:02}:{end % 60:02}:00",
                        "price": 75000 if start < 1080 else 90000,
                        "outOfService": index == 38,
                    })
                return self.reply(200, {"courtId": court, "date": day,
                                        "outOfService": False, "slots": slots})
            if parts[5] == "slot-occupancy":
                return self.reply(200, {"courtId": court, "date": day, "bookedSlotIds": [37]})
            self.reply(404, {})
        except (ValueError, KeyError):
            self.reply(400, {})

    def reply(self, status, body):
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


if __name__ == "__main__":
    print("SCRUM-22 fixtures: http://127.0.0.1:8096 (court 1 only)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8096), Handler).serve_forever()
