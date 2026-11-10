"""Route geometry in a local equirectangular frame (metres). Accurate to well under 1 % at 2 km scales."""
import math

EARTH_RADIUS_M = 6371000.0


def haversine_m(a, b):
    """a, b = (lon, lat)."""
    lat1, lat2 = math.radians(a[1]), math.radians(b[1])
    dlat, dlon = lat2 - lat1, math.radians(b[0] - a[0])
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


class Route:
    """A polyline of (lon, lat) points with cumulative distance, for nearest-point queries."""

    def __init__(self, points):
        if len(points) < 2:
            raise ValueError("route needs two points")
        self.points = [tuple(p) for p in points]
        self.cumulative = [0.0]
        for a, b in zip(self.points, self.points[1:]):
            self.cumulative.append(self.cumulative[-1] + haversine_m(a, b))
        lons = [p[0] for p in self.points]
        lats = [p[1] for p in self.points]
        self.bounds = (min(lons), min(lats), max(lons), max(lats))
        self.length_m = self.cumulative[-1]

    def nearest(self, lon, lat, max_distance_m=None):
        """(distance_m, along_m, side) of the closest route point; side is +1 right / -1 left of travel.

        Returns None when a cheap bounding-box test proves the point is farther than max_distance_m.
        """
        if max_distance_m is not None:
            pad_lat = max_distance_m / 111_000.0
            pad_lon = pad_lat / max(0.2, math.cos(math.radians(lat)))
            min_lon, min_lat, max_lon, max_lat = self.bounds
            if not (min_lon - pad_lon <= lon <= max_lon + pad_lon and min_lat - pad_lat <= lat <= max_lat + pad_lat):
                return None
        kx = math.cos(math.radians(lat)) * math.pi / 180 * EARTH_RADIUS_M
        ky = math.pi / 180 * EARTH_RADIUS_M
        best = None
        for index in range(len(self.points) - 1):
            ax, ay = (self.points[index][0] - lon) * kx, (self.points[index][1] - lat) * ky
            bx, by = (self.points[index + 1][0] - lon) * kx, (self.points[index + 1][1] - lat) * ky
            dx, dy = bx - ax, by - ay
            length2 = dx * dx + dy * dy
            t = 0.0 if length2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / length2))
            px, py = ax + t * dx, ay + t * dy
            distance = math.hypot(px, py)
            if best is None or distance < best[0]:
                # Cross product of travel direction and (route point -> place): negative = place on the right.
                cross = dx * (-py) - dy * (-px)
                side = 1 if cross < 0 else -1
                best = (distance, self.cumulative[index] + t * math.sqrt(length2), side)
        if max_distance_m is not None and best[0] > max_distance_m:
            return None
        return best

    def samples(self, spacing_m):
        """Points every spacing_m along the route (first and last included)."""
        out, next_at = [], 0.0
        for index in range(len(self.points) - 1):
            a, b = self.points[index], self.points[index + 1]
            start, end = self.cumulative[index], self.cumulative[index + 1]
            while next_at <= end and end > start:
                t = (next_at - start) / (end - start)
                out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
                next_at += spacing_m
        out.append(self.points[-1])
        return out


def simplify(points, tolerance_m):
    """Douglas-Peucker in a local frame; keeps endpoints."""
    if len(points) <= 2:
        return list(points)
    lat0 = sum(p[1] for p in points) / len(points)
    kx = math.cos(math.radians(lat0)) * math.pi / 180 * EARTH_RADIUS_M
    ky = math.pi / 180 * EARTH_RADIUS_M
    xy = [(p[0] * kx, p[1] * ky) for p in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        ax, ay = xy[first]
        bx, by = xy[last]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        worst, worst_index = -1.0, None
        for i in range(first + 1, last):
            px, py = xy[i]
            d = abs(dx * (ay - py) - dy * (ax - px)) / length if length else math.hypot(px - ax, py - ay)
            if d > worst:
                worst, worst_index = d, i
        if worst_index is not None and worst > tolerance_m:
            keep[worst_index] = True
            stack.append((first, worst_index))
            stack.append((worst_index, last))
    return [p for p, k in zip(points, keep) if k]
