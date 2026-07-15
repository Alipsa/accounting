<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>${title}</h2>
  <p class="lead">${lead}</p>
  <ul class="summary">
    <#list summaryLines as line>
      <li>${line}</li>
    </#list>
  </ul>
  <table class="report-table dense-report-table general-ledger-table">
    <colgroup>
      <col class="account-col">
      <col class="name-col">
      <col class="date-col">
      <col class="voucher-col">
      <col class="text-col">
      <col class="amount-col">
      <col class="amount-col">
      <col class="balance-col">
    </colgroup>
    <thead>
      <tr>
        <th class="code">${tableHeaders[0]}</th>
        <th>${tableHeaders[1]}</th>
        <th class="date">${tableHeaders[2]}</th>
        <th>${tableHeaders[3]}</th>
        <th>${tableHeaders[4]}</th>
        <th class="number">${tableHeaders[5]}</th>
        <th class="number">${tableHeaders[6]}</th>
        <th class="number">${tableHeaders[7]}</th>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <td class="code">${row[0]}</td>
          <td>${row[1]}</td>
          <td class="date">${row[2]}</td>
          <td>${row[3]}</td>
          <td>${row[4]}</td>
          <td class="number">${row[5]}</td>
          <td class="number">${row[6]}</td>
          <td class="number">${row[7]}</td>
        </tr>
      </#list>
    </tbody>
  </table>
  <p class="note">${note}</p>
</@layout.page>
