package gopeed

import (
	"errors"
	"os"
	"path/filepath"
)

const destinationCacheName = ".asterlink-cache"

func validPayloadRoot(root string) bool {
	return filepath.IsAbs(root) && (filepath.Clean(root) == payloadRoot || filepath.Base(root) == destinationCacheName)
}

// Old requests retain their original cache root and encrypted checkpoints.
// Desktop requests may place payloads under the chosen download directory.
func taskPayloadDirectory(input request) (string, error) {
	root := payloadRoot
	if input.CacheRoot != "" {
		root = filepath.Clean(input.CacheRoot)
	}
	if !validPayloadRoot(root) || !safeID.MatchString(input.ID) {
		return "", errors.New("invalid task cache directory")
	}
	dir := filepath.Join(root, input.ID)
	if root == payloadRoot {
		// Preserve Android and older desktop cache handling, including a root
		// redirected by the OS. Only custom destination roots need new checks.
		if err := os.MkdirAll(dir, 0700); err != nil {
			return "", err
		}
		return dir, realCacheDirectory(dir)
	}
	for _, path := range []string{root, dir} {
		if err := os.MkdirAll(path, 0700); err != nil {
			return "", err
		}
		if err := realCacheDirectory(path); err != nil {
			return "", err
		}
	}
	return dir, checkTaskPayloadDirectory(root, dir)
}

func realCacheDirectory(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("task cache directory cannot be a link or file")
	}
	return nil
}

func checkTaskPayloadDirectory(root, dir string) error {
	if root == payloadRoot {
		return realCacheDirectory(dir)
	}
	for _, path := range []string{root, dir} {
		if err := realCacheDirectory(path); err != nil {
			return err
		}
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return err
	}
	resolvedTask, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return err
	}
	if resolvedTask != filepath.Join(resolvedRoot, filepath.Base(dir)) {
		return errors.New("task cache directory escaped its root")
	}
	return nil
}
