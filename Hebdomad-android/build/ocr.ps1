Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.StorageFile, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
[Windows.Globalization.Language, Windows.Foundation, ContentType=WindowsRuntime] | Out-Null
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.IsGenericMethod })[0]
function Await($operation, $resultType) {
    $task=$asTask.MakeGenericMethod($resultType).Invoke($null,@($operation));$task.Wait();$task.Result
}
$lang=New-Object Windows.Globalization.Language('zh-Hans-CN')
$engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($lang)
$results=@()
foreach($path in Get-ChildItem "$PSScriptRoot/ocr-*.png"){
    $file=Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path.FullName)) ([Windows.Storage.StorageFile])
    $stream=Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder=Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap=Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $result=Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    $words=@();foreach($line in $result.Lines){foreach($word in $line.Words){$words+=@{text=$word.Text;x=$word.BoundingRect.X;y=$word.BoundingRect.Y;w=$word.BoundingRect.Width;h=$word.BoundingRect.Height}}}
    $results+=@{file=$path.Name;words=$words};$bitmap.Dispose();$stream.Dispose()
}
ConvertTo-Json -Depth 6 -InputObject $results | Set-Content -Encoding UTF8 "$PSScriptRoot/ocr-result.json"
