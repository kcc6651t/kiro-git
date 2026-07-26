package fileops

// NewBadRequest builds a PathError representing a malformed request.
func NewBadRequest(msg string) error {
	return newPathError("BAD_REQUEST", msg)
}

// CodeOf extracts the stable error code from an error, defaulting to READ_FAILED.
func CodeOf(err error) string {
	if pe, ok := err.(*PathError); ok {
		return pe.Code
	}
	return "READ_FAILED"
}
