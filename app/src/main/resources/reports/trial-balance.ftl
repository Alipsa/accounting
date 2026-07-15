<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>${title}</h2>
  <div class="metrics">
    <#list summaryLines as line>
      <div class="metric">
        <span class="metric-label">${line?keep_before(':')}</span>
        <span class="metric-value">${line?keep_after(': ')}</span>
      </div>
    </#list>
  </div>
  <table class="report-table trial-balance-table">
    <colgroup>
      <col class="account-col">
      <col class="name-col">
      <col class="amount-col">
      <col class="amount-col">
      <col class="amount-col">
      <col class="amount-col">
    </colgroup>
    <thead>
      <tr>
        <th class="code">${tableHeaders[0]}</th>
        <th>${tableHeaders[1]}</th>
        <th class="number">${tableHeaders[2]}</th>
        <th class="number">${tableHeaders[3]}</th>
        <th class="number">${tableHeaders[4]}</th>
        <th class="number">${tableHeaders[5]}</th>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <td class="code">${row[0]}</td>
          <td>${row[1]}</td>
          <td class="number">${row[2]}</td>
          <td class="number">${row[3]}</td>
          <td class="number">${row[4]}</td>
          <td class="number">${row[5]}</td>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
