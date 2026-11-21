package sender

import "bytes"

// SendBatch は JSON（1 件ずつ）をまとめて 1 つの配列にして送信します。
func (s *Sender) SendBatch(items [][]byte) error {
	if len(items) == 0 {
		return nil
	}
	var b bytes.Buffer
	b.WriteByte('[')
	b.Write(bytes.Join(items, []byte{','}))
	b.WriteByte(']')
	return s.Send(b.Bytes())
}
