package jfr

// Get walks a decoded event by path and returns the value found there, or nil
// when the path cannot be followed.
//
// Path segments are either a string, naming a field of a map, or an int,
// indexing an *Array. Constant pool references are resolved as the path is
// walked, and a *Ref found at the end of the path is resolved too, so
//
//	jfr.Get(event.Values, "stackTrace", "frames", 0, "method", "name")
//
// reads through the stack trace, frame, method and symbol constant pools
// without the caller having to resolve anything by hand.
func Get(root map[string]any, path ...any) any {
	var current any = root
	for _, segment := range path {
		if ref, ok := current.(*Ref); ok {
			current = ref.Value()
		}
		switch key := segment.(type) {
		case string:
			m, ok := current.(map[string]any)
			if !ok {
				return nil
			}
			current = m[key]
		case int:
			arr, ok := current.(*Array)
			if !ok || key < 0 || key >= len(arr.Values) {
				return nil
			}
			current = arr.Values[key]
		default:
			return nil
		}
	}
	return Resolve(current)
}

// GetString is Get for values known to be strings. The second result reports
// whether the path resolved to a string.
func GetString(root map[string]any, path ...any) (string, bool) {
	s, ok := Get(root, path...).(string)
	return s, ok
}

// GetInt is Get for values known to be integral. It accepts every integral JFR
// type and reports whether the path resolved to one.
func GetInt(root map[string]any, path ...any) (int64, bool) {
	switch v := Get(root, path...).(type) {
	case int64:
		return v, true
	case int16:
		return int64(v), true
	case int8:
		return int64(v), true
	case uint16:
		return int64(v), true
	default:
		return 0, false
	}
}

// GetMap is Get for values known to be complex. The second result reports
// whether the path resolved to a complex value.
func GetMap(root map[string]any, path ...any) (map[string]any, bool) {
	m, ok := Get(root, path...).(map[string]any)
	return m, ok
}

// Resolve resolves a constant pool reference to its value. Values that are not
// references are returned unchanged.
func Resolve(v any) any {
	if ref, ok := v.(*Ref); ok {
		return ref.Value()
	}
	return v
}

// ResolveDeep resolves every constant pool reference reachable from v,
// returning a structure built only from maps, slices and scalars.
//
// Resolved constant pool entries are memoised per entry, so a stack trace
// shared by ten thousand events is materialised once rather than once per
// event. The result is therefore NOT a private copy: sub-structures reached
// through a constant pool reference are shared between calls and between
// events, and must be treated as read-only. The map passed in is always copied,
// so the event's own fields can be modified freely.
//
// Reference cycles, which the constant pools of a JFR recording can contain
// through the class/class loader graph, are broken by returning nil for an
// entry already being resolved on the current path. Entries whose subtree
// crosses such a back edge are resolved but not memoised, because their value
// depends on where the walk entered them.
func ResolveDeep(v any) any {
	value, _ := resolveDeep(v, make(map[deepKey]int), 0)
	return value
}

// ResolveDeepMap is ResolveDeep for a decoded event.
func ResolveDeepMap(m map[string]any) map[string]any {
	resolved, _ := ResolveDeep(m).(map[string]any)
	return resolved
}

// deepKey identifies a constant pool entry across a deep resolution.
type deepKey struct {
	pool  *constantPool
	index int64
}

// noBackEdge marks a subtree that contains no reference back to an entry still
// being resolved, and is therefore safe to memoise.
const noBackEdge = int(^uint(0) >> 1)

// resolveDeep returns the resolved value along with the shallowest path depth
// any back edge in its subtree points at. A value is safe to memoise only when
// that depth is below its own, meaning no cycle reaches into it.
func resolveDeep(v any, onPath map[deepKey]int, depth int) (any, int) {
	switch value := v.(type) {
	case *Ref:
		if value.pool == nil {
			return nil, noBackEdge
		}
		k := deepKey{value.pool, value.index}
		if cached, ok := value.pool.deepCache[k.index]; ok {
			return cached, noBackEdge
		}
		if at, ok := onPath[k]; ok {
			return nil, at
		}
		onPath[k] = depth
		resolved, minBack := resolveDeep(value.Value(), onPath, depth+1)
		delete(onPath, k)
		if minBack > depth {
			if value.pool.deepCache == nil {
				value.pool.deepCache = make(map[int64]any)
			}
			value.pool.deepCache[k.index] = resolved
		}
		return resolved, minBack
	case map[string]any:
		out := make(map[string]any, len(value))
		minBack := noBackEdge
		for name, item := range value {
			resolved, back := resolveDeep(item, onPath, depth)
			if back < minBack {
				minBack = back
			}
			out[name] = resolved
		}
		return out, minBack
	case *Array:
		out := make([]any, len(value.Values))
		minBack := noBackEdge
		for i, item := range value.Values {
			resolved, back := resolveDeep(item, onPath, depth)
			if back < minBack {
				minBack = back
			}
			out[i] = resolved
		}
		return out, minBack
	default:
		return v, noBackEdge
	}
}
