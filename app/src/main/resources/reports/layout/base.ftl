<#macro page title>
<!DOCTYPE html>
<html lang="sv">
<head>
  <meta charset="utf-8" />
  <title>${title}</title>
  <style>
    <#include "styles.css">
  </style>
</head>
<body>
  <header>
    <h1>${companyName}</h1>
    <p class="meta">${selectionLabel}</p>
    <#if organizationNumber?has_content>
      <p class="meta">Org.nr: ${organizationNumber}</p>
    </#if>
  </header>
  <main>
    <#nested>
  </main>
</body>
</html>
</#macro>
