<#macro page title>
<!DOCTYPE html>
<html lang="${htmlLang!'en'}">
<head>
  <meta charset="utf-8" />
  <title>${title}</title>
  <style>
    <#include "styles.css">
  </style>
</head>
<body>
  <header class="report-masthead">
    <div class="masthead-title">${companyName}</div>
    <div class="masthead-meta">
      <div>${selectionLabel}</div>
      <#if organizationNumber?has_content>
        <div>${orgNumberLabel!'Org. no.:'} ${organizationNumber}</div>
      </#if>
    </div>
  </header>
  <main>
    <#nested>
  </main>
</body>
</html>
</#macro>
