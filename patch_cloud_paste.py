import re

file_path = r'D:\github web\RasFocus-final\Rasfocus-final\app\src\main\java\com\rasel\RasFocus\filemanager\FileManagerUI.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix LocalFileScreen Paste (though onSetClipboard(null) is already there, let's make sure it's correct)
# Actually, LocalFileScreen Paste has onSetClipboard(null) at line 649, which is correct.

# Fix CloudFileScreen Paste (lines ~1130-1215)
pattern = re.compile(r'(if \(clipboard\.sourceEnv == "Local"\) \{.*?)\n\s+withContext\(kotlinx\.coroutines\.Dispatchers\.Main\) \{\n\s+Toast\.makeText\(context.*?\n\s+onSetClipboard\(null\).*?\n\s+isLoading = true.*?\n\s+rawFiles = .*?\n\s+isLoading = false\n\s+\}', re.DOTALL)

match = pattern.search(content)
if match:
    old_block = match.group(0)
    
    # We want to pull onSetClipboard(null) out to the top, and add a FileOperation
    new_block = '''val opId = java.util.UUID.randomUUID().toString()
                                val op = FileOperation(
                                    id = opId,
                                    type = if (clipboard.isCut) OperationType.MOVE else OperationType.COPY,
                                    sourceCount = clipboard.items.size,
                                    itemsProcessed = 0
                                )
                                FileOperationManager.addOperation(op)
                                onSetClipboard(null)
                                
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    var success = true
                                    ''' + match.group(1).replace('scope.launch(kotlinx.coroutines.Dispatchers.IO) {', '') + '''
                                    FileOperationManager.updateOperation(opId) { it.copy(isComplete = true) }
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, if (success) "Pasted successfully" else "Some items failed to paste", Toast.LENGTH_SHORT).show()
                                        isLoading = true
                                        rawFiles = DriveFileManager.listFiles(context, accountName, folderId) ?: emptyList()
                                        isLoading = false
                                    }'''
    
    # Wait, the original code had scope.launch(kotlinx.coroutines.Dispatchers.IO) { BEFORE the if block.
    # Let's just do a simpler replacement.
    pass
else:
    print("Cloud paste pattern not found!")

