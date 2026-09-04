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
// returning a structure built only from maps, slices and scalars. Reference
// cycles, which the constant pools of a JFR recording routinely contain, are
// broken by returning nil for an entry already being resolved on the current
// path.
//
// The result is a copy; the caller may modify it freely. Resolving a whole
// event deeply can be expensive, as it materialises everything the event
// transitively references.
func ResolveDeep(v any) any {
	return resolveDeep(v, make(map[*constantPool]map[int64]bool))
}

// ResolveDeepMap is ResolveDeep for a decoded event.
func ResolveDeepMap(m map[string]any) map[string]any {
	resolved, _ := ResolveDeep(m).(map[string]any)
	return resolved
}

func resolveDeep(v any, visiting map[*constantPool]map[int64]bool) any {
	switch value := v.(type) {
	case *Ref:
		if value.pool == nil {
			return nil
		}
		seen := visiting[value.pool]
		if seen == nil {
			seen = make(map[int64]bool)
			visiting[value.pool] = seen
		}
		if seen[value.index] {
			return nil
		}
		seen[value.index] = true
		defer delete(seen, value.index)
		return resolveDeep(value.Value(), visiting)
	case map[string]any:
		out := make(map[string]any, len(value))
		for k, item := range value {
			out[k] = resolveDeep(item, visiting)
		}
		return out
	case *Array:
		out := make([]any, len(value.Values))
		for i, item := range value.Values {
			out[i] = resolveDeep(item, visiting)
		}
		return out
	default:
		return v
	}
}
