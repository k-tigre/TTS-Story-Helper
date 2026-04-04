# change-git-author.ps1
# Скрипт для изменения автора во всех коммитах Git-репозитория (Windows / PowerShell)

# Установка кодировки консоли на UTF-8
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$PSDefaultParameterValues['*:Encoding'] = 'utf8'

# Запрос текущих данных (старый email или имя)
$oldEmail = "ktigre@yandex-team.ru"
$newName  =  "Tigre"
$newEmail =  "k.gorbats@gmail.com"

Write-Host ""
Write-Host "Будут изменены все коммиты с автором, у которого email = '$oldEmail'."
Write-Host "Новый автор: $newName <$newEmail>"
$confirm = Read-Host "Продолжить? (y/n)"

if ($confirm -ne 'y') {
    Write-Host "Операция отменена."
    exit
}

if (-not (Test-Path ".git")) {
    Write-Host "Ошибка: текущая папка не является корнем Git-репозитория (нет папки .git)."
    exit
}

Write-Host "Запуск git filter-repo. Это может занять некоторое время..."

git filter-repo --force --commit-callback @"
if commit.author_email == b'$oldEmail':
    commit.author_name = b'$newName'
    commit.author_email = b'$newEmail'
    commit.committer_name = b'$newName'
    commit.committer_email = b'$newEmail'
"@

if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ История переписана. Важно: filter-repo удалил привязку к удалённому репозиторию."
    Write-Host "Восстановите её и отправьте изменения:"
    Write-Host "   git remote add origin <URL-репозитория>"
    Write-Host "   git push --force-with-lease"
    Write-Host ""
    Write-Host "Если в репозитории были другие удалённые ветки, добавьте их аналогично."
} else {
    Write-Host "❌ Произошла ошибка. Проверьте ввод и повторите попытку."
}
